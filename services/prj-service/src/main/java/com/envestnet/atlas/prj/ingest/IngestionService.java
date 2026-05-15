package com.envestnet.atlas.prj.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Pulls customer source code into a per-project workspace under
 * {@code /uploads/{projectId}} so the downstream analysis agents
 * (arch, cap, recon, uplift) can walk it through the same shared-volume
 * contract as the bundled {@code /samples} fixtures.
 *
 * <p>Two ingestion modes:
 * <ul>
 *   <li><b>GitHub clone</b> — sparse-checkout-capable shallow clone via the
 *       {@code git} CLI baked into prj-service's container image. Supports
 *       an optional {@code subpath} so a stakeholder can paste a giant
 *       monorepo URL but pull only the relevant subtree.</li>
 *   <li><b>Archive upload</b> — accepts a multipart {@code .zip} (the most
 *       portable format from a Windows / Mac developer). Extracted with
 *       zip-slip protection — every entry's resolved path must stay
 *       inside the project directory.</li>
 * </ul>
 *
 * <p>Both modes write into the same per-project directory and progress
 * is tracked in an in-memory map keyed by project id so the SPA can
 * poll a single status endpoint regardless of which mode it picked.</p>
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    /** Cap to prevent a malicious / runaway clone from filling the disk. */
    public static final long MAX_INGEST_BYTES = 500L * 1024 * 1024;   // 500 MB
    /** Cap individual entries in a zip — a tiny zip with a tiny bomb is real. */
    public static final long MAX_ENTRY_BYTES   = 100L * 1024 * 1024;  // 100 MB
    /** Hard timeout for the git clone. */
    public static final int GIT_TIMEOUT_SECONDS = 300;

    /**
     * Whitelist of host strings we'll allow. Anything else 400s. Keeps
     * the clone surface from being abused as a generic SSRF proxy.
     */
    private static final Pattern ALLOWED_GIT_HOST =
            Pattern.compile("^https://(github\\.com|gitlab\\.com|bitbucket\\.org)/.+\\.?(git)?/?$",
                    Pattern.CASE_INSENSITIVE);

    public enum State { PENDING, CLONING, EXTRACTING, READY, ERROR }

    public record Status(
            UUID projectId,
            State state,
            String mode,            // "github" | "upload" | null
            String message,         // human-readable status
            String sourcePath,      // /uploads/{projectId}/... (post-ready)
            long bytes,
            int files,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt
    ) {}

    private final Path uploadsRoot;
    private final Map<UUID, Status> statuses = new ConcurrentHashMap<>();

    public IngestionService(@Value("${UPLOADS_DIR:/uploads}") String uploadsDir) {
        this.uploadsRoot = Paths.get(uploadsDir);
        try {
            Files.createDirectories(uploadsRoot);
        } catch (IOException e) {
            log.warn("could not pre-create uploads root {} — will retry per-project · {}",
                    uploadsRoot, e.toString());
        }
    }

    public Status status(UUID projectId) {
        return statuses.getOrDefault(projectId,
                new Status(projectId, State.PENDING, null, "no ingestion started",
                        null, 0, 0, null, null));
    }

    /**
     * Run a GitHub clone synchronously. The SPA calls this from a
     * react-query mutation and shows a spinner; for the sample-sized
     * repos we target (and especially with sparse-checkout + depth=1)
     * this completes in ~5–15 s, well within an interactive wait.
     * Idempotent on the project — repeated calls overwrite the
     * previous clone.
     */
    public Status ingestFromGithub(UUID projectId, String url, String branch, String subpath) {
        Status start = new Status(projectId, State.CLONING, "github",
                "Cloning " + safeForLog(url) + (subpath != null && !subpath.isBlank() ? " · " + subpath : ""),
                null, 0, 0, OffsetDateTime.now(), null);
        statuses.put(projectId, start);

        if (url == null || !ALLOWED_GIT_HOST.matcher(url.trim()).matches()) {
            return fail(projectId, "github",
                    "Only github.com / gitlab.com / bitbucket.org HTTPS URLs are allowed.");
        }
        Path projectDir = uploadsRoot.resolve(projectId.toString());
        try {
            // Idempotent: wipe a prior clone if it exists.
            if (Files.exists(projectDir)) wipe(projectDir);
            Files.createDirectories(projectDir);

            // Sparse-checkout pattern: `--depth 1 --filter=blob:none
            // --sparse` keeps the working tree empty until we narrow it
            // via `sparse-checkout set <path>`. Skipping that step gives
            // a full clone.
            int code;
            String branchArg = (branch != null && !branch.isBlank()) ? branch.trim() : null;
            if (subpath != null && !subpath.isBlank()) {
                code = runProcess(projectDir,
                        "git", "clone", "--depth", "1",
                               "--filter=blob:none", "--sparse",
                               branchArg != null ? "--branch" : null, branchArg,
                               url.trim(), ".");
                if (code == 0) {
                    code = runProcess(projectDir,
                            "git", "sparse-checkout", "set", subpath.trim());
                }
            } else {
                code = runProcess(projectDir,
                        "git", "clone", "--depth", "1",
                               branchArg != null ? "--branch" : null, branchArg,
                               url.trim(), ".");
            }
            if (code != 0) {
                return fail(projectId, "github", "git clone exited with code " + code);
            }

            // Compute final byte total and file count for the status row.
            long[] totals = measure(projectDir);
            if (totals[0] > MAX_INGEST_BYTES) {
                wipe(projectDir);
                return fail(projectId, "github",
                        "Cloned tree exceeded the " + (MAX_INGEST_BYTES / 1024 / 1024)
                                + " MB ingest cap (" + totals[0] / 1024 / 1024 + " MB)");
            }

            // If a subpath was requested, point the agents at the resolved
            // location inside the sparse-checkout tree.
            Path sourcePath = projectDir;
            if (subpath != null && !subpath.isBlank()) {
                Path candidate = projectDir.resolve(subpath.trim());
                if (Files.isDirectory(candidate)) sourcePath = candidate;
            }

            return ready(projectId, "github", sourcePath, totals[0], (int) totals[1]);
        } catch (Exception e) {
            log.warn("github ingest failed · project={} · {}", projectId, e.toString());
            return fail(projectId, "github", e.getMessage());
        }
    }

    /**
     * Extract a user-uploaded archive into the project workspace.
     * Zip-slip protected — entries whose resolved path escapes the
     * project directory are rejected.
     */
    public Status ingestFromUpload(UUID projectId, MultipartFile file) {
        Status start = new Status(projectId, State.EXTRACTING, "upload",
                "Extracting " + file.getOriginalFilename(),
                null, 0, 0, OffsetDateTime.now(), null);
        statuses.put(projectId, start);

        if (file.getSize() > MAX_INGEST_BYTES) {
            return fail(projectId, "upload",
                    "Archive size " + file.getSize() + " exceeds the "
                            + MAX_INGEST_BYTES + " byte ingest cap.");
        }
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase().endsWith(".zip")) {
            return fail(projectId, "upload",
                    "Only .zip archives are accepted (got " + name + ")");
        }

        Path projectDir = uploadsRoot.resolve(projectId.toString());
        try {
            if (Files.exists(projectDir)) wipe(projectDir);
            Files.createDirectories(projectDir);

            long totalBytes = 0;
            int totalFiles = 0;
            try (InputStream raw = file.getInputStream();
                 ZipInputStream zip = new ZipInputStream(raw)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    Path target = projectDir.resolve(entry.getName()).normalize();
                    // Zip-slip guard: every resolved path must stay
                    // under projectDir. A maliciously crafted entry
                    // like `../../etc/passwd` would otherwise escape.
                    if (!target.startsWith(projectDir)) {
                        wipe(projectDir);
                        return fail(projectId, "upload",
                                "Archive contains an entry that resolves outside the project directory: "
                                        + entry.getName());
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(target);
                        continue;
                    }
                    Files.createDirectories(target.getParent());
                    // Stream the entry but enforce a per-entry cap.
                    long written = Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                    if (written > MAX_ENTRY_BYTES) {
                        wipe(projectDir);
                        return fail(projectId, "upload",
                                "Archive entry exceeds the per-file cap: " + entry.getName());
                    }
                    totalBytes += written;
                    totalFiles++;
                    if (totalBytes > MAX_INGEST_BYTES) {
                        wipe(projectDir);
                        return fail(projectId, "upload",
                                "Archive expands beyond the " + (MAX_INGEST_BYTES / 1024 / 1024)
                                        + " MB total ingest cap.");
                    }
                }
            }
            return ready(projectId, "upload", projectDir, totalBytes, totalFiles);
        } catch (Exception e) {
            log.warn("upload ingest failed · project={} · {}", projectId, e.toString());
            return fail(projectId, "upload", e.getMessage());
        }
    }

    /* ---------------- internals ---------------- */

    private Status ready(UUID projectId, String mode, Path sourcePath, long bytes, int files) {
        // The agents read from /uploads as a read-only volume mount,
        // so the path stored on Project.sourcePath must be the
        // canonical absolute path inside the container.
        String canonical = sourcePath.toAbsolutePath().toString().replace('\\', '/');
        OffsetDateTime startedAt = statuses.get(projectId) != null
                ? statuses.get(projectId).startedAt() : OffsetDateTime.now();
        Status s = new Status(projectId, State.READY, mode,
                "Ready (" + files + " files, " + (bytes / 1024) + " KB)",
                canonical, bytes, files, startedAt, OffsetDateTime.now());
        statuses.put(projectId, s);
        log.info("ingest ready · project={} mode={} files={} bytes={} sourcePath={}",
                projectId, mode, files, bytes, canonical);
        return s;
    }

    private Status fail(UUID projectId, String mode, String msg) {
        Status s = new Status(projectId, State.ERROR, mode,
                msg == null ? "ingest failed" : msg, null, 0, 0,
                statuses.get(projectId) != null ? statuses.get(projectId).startedAt() : OffsetDateTime.now(),
                OffsetDateTime.now());
        statuses.put(projectId, s);
        log.warn("ingest error · project={} mode={} · {}", projectId, mode, msg);
        return s;
    }

    /**
     * Run a process and inherit working directory; null arguments are
     * silently dropped (callers pass {@code null} for optional flags
     * like {@code --branch}).
     */
    private int runProcess(Path cwd, String... rawArgs) throws IOException, InterruptedException {
        java.util.List<String> args = new java.util.ArrayList<>(rawArgs.length);
        for (String a : rawArgs) {
            if (a != null) args.add(a);
        }
        ProcessBuilder pb = new ProcessBuilder(args).directory(cwd.toFile()).redirectErrorStream(true);
        Process p = pb.start();
        if (!p.waitFor(GIT_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("git timed out after " + GIT_TIMEOUT_SECONDS + " seconds");
        }
        // Drain stdout (which has stderr merged in) so the buffer
        // doesn't deadlock if git produced a lot of output.
        try (var in = p.getInputStream()) {
            byte[] tail = in.readAllBytes();
            if (tail.length > 0 && p.exitValue() != 0) {
                log.warn("git output (last 1KB): {}",
                        new String(tail, 0, Math.min(tail.length, 1024)));
            }
        }
        return p.exitValue();
    }

    private long[] measure(Path root) throws IOException {
        final long[] totals = new long[2];   // [bytes, files]
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !p.toString().contains("/.git/") && !p.toString().contains("\\.git\\"))
                    .forEach(p -> {
                        try { totals[0] += Files.size(p); totals[1]++; }
                        catch (IOException ignored) {}
                    });
        }
        return totals;
    }

    private void wipe(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        }
    }

    /** Trim repo URLs for log lines — leave host visible, hide the path tail. */
    private static String safeForLog(String url) {
        if (url == null) return "(null)";
        Matcher m = Pattern.compile("^(https://[^/]+)/.+$").matcher(url);
        return m.matches() ? m.group(1) + "/…" : url;
    }
}
