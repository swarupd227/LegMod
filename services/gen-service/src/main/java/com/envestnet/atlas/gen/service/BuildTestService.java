package com.envestnet.atlas.gen.service;

import com.envestnet.atlas.gen.generator.BuildTestRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Orchestrates the Stage F "Build & Test" gate.
 *
 * <p>Given a project and its track (SOAP or UPLIFT), resolves which
 * source tree to compile-and-test, hands it to {@link BuildTestRunner},
 * and persists the structured result in {@code gen.build_run}. The
 * resulting row is what the SPA's Stage F screen reads to show pass
 * counts, failures, and the compile log.</p>
 *
 * <p>SOAP track: targets the wsimport-generated Java tree at
 * {@code /tmp/atlas-gen/<projectId>/src} (the same scratch dir the
 * GenerationService writes to). We synthesise a pom.xml and run
 * {@code mvn compile}. There are typically no unit tests in
 * wsimport output, so the test counts come back zero — reported as
 * "compile clean, no tests".</p>
 *
 * <p>UPLIFT track: targets the customer's source tree at the
 * project's {@code sourcePath}. Atlas's OpenRewrite recipes have
 * already modified it in place; we run {@code mvn test} to prove
 * the changes didn't break the existing test suite.</p>
 */
@Service
public class BuildTestService {

    private static final Logger log = LoggerFactory.getLogger(BuildTestService.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
    private final String genWorkRoot;
    private final int defaultTimeoutSeconds;

    public BuildTestService(JdbcTemplate jdbc,
                            @Value("${GEN_WORK_ROOT:/tmp/atlas-gen}") String genWorkRoot,
                            @Value("${BUILD_TEST_TIMEOUT_SECONDS:90}") int defaultTimeoutSeconds) {
        this.jdbc = jdbc;
        this.genWorkRoot = genWorkRoot;
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
    }

    public Map<String, Object> run(UUID projectId, String track, String sourcePath,
                                   String basePackage) {
        // Insert a "running" row immediately so the SPA can poll and
        // show progress. We'll UPDATE it with results when done.
        UUID runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO gen.build_run
                    (id, project_id, started_at, status, track, log_text)
                VALUES (?, ?, now(), 'running', ?, '')
                """, runId, projectId, track);

        BuildTestRunner runner = new BuildTestRunner();
        BuildTestRunner.Result result;
        try {
            Path workDir = resolveWorkDir(projectId, track, sourcePath);
            if (!Files.exists(workDir) || !Files.isDirectory(workDir)) {
                throw new IllegalStateException(
                        "Build target does not exist: " + workDir);
            }
            result = "SOAP".equalsIgnoreCase(track)
                    ? runner.compileGenerated(workDir, basePackage, defaultTimeoutSeconds)
                    : runner.buildAndTestExisting(workDir, defaultTimeoutSeconds);
        } catch (Exception e) {
            log.error("Build+test failed for project {}: {}", projectId, e.toString(), e);
            result = new BuildTestRunner.Result();
            result.status = "error";
            result.track = track;
            result.logText = "Atlas could not run the build/test cycle:\n" + e.getMessage();
        }

        // Persist the structured result.
        String failuresJson;
        try { failuresJson = json.writeValueAsString(result.failures); }
        catch (Exception e) { failuresJson = "[]"; }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("startedAt",  OffsetDateTime.now().toString());
        summary.put("track",      result.track);
        summary.put("status",     result.status);
        summary.put("duration_ms", result.durationMs);
        String summaryJson;
        try { summaryJson = json.writeValueAsString(summary); }
        catch (Exception e) { summaryJson = "{}"; }

        jdbc.update("""
                UPDATE gen.build_run SET
                    finished_at      = now(),
                    status           = ?,
                    track            = ?,
                    files_compiled   = ?,
                    compile_errors   = ?,
                    tests_total      = ?,
                    tests_passed     = ?,
                    tests_failed     = ?,
                    tests_skipped    = ?,
                    duration_ms      = ?,
                    log_text         = ?,
                    failures         = ?::jsonb,
                    summary          = ?::jsonb
                  WHERE id = ?
                """,
                result.status, result.track, result.filesCompiled, result.compileErrors,
                result.testsTotal, result.testsPassed, result.testsFailed, result.testsSkipped,
                result.durationMs,
                result.logText == null ? "" : result.logText,
                failuresJson, summaryJson, runId);

        return materialise(runId, result);
    }

    /**
     * Latest build_run for a project. Returns an empty map (not 404)
     * when there is none, so the SPA can render the "not run yet"
     * state with no error noise.
     */
    public Map<String, Object> status(UUID projectId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, project_id, started_at, finished_at, status, track,
                       files_compiled, compile_errors,
                       tests_total, tests_passed, tests_failed, tests_skipped,
                       duration_ms, log_text,
                       failures::text  AS failures_json,
                       summary::text   AS summary_json
                  FROM gen.build_run
                 WHERE project_id = ?
                 ORDER BY started_at DESC
                 LIMIT 1
                """, projectId);
        if (rows.isEmpty()) {
            // Map.of(...) rejects nulls, so use a LinkedHashMap here.
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("run", null);
            return empty;
        }
        Map<String, Object> r = rows.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id",             r.get("id"));
        out.put("projectId",      r.get("project_id"));
        out.put("startedAt",      r.get("started_at"));
        out.put("finishedAt",     r.get("finished_at"));
        out.put("status",         r.get("status"));
        out.put("track",          r.get("track"));
        out.put("filesCompiled",  r.get("files_compiled"));
        out.put("compileErrors",  r.get("compile_errors"));
        out.put("testsTotal",     r.get("tests_total"));
        out.put("testsPassed",    r.get("tests_passed"));
        out.put("testsFailed",    r.get("tests_failed"));
        out.put("testsSkipped",   r.get("tests_skipped"));
        out.put("durationMs",     r.get("duration_ms"));
        out.put("logText",        r.get("log_text"));
        try {
            out.put("failures", json.readValue((String) r.get("failures_json"), List.class));
        } catch (Exception e) { out.put("failures", List.of()); }
        return Map.of("run", out);
    }

    private Path resolveWorkDir(UUID projectId, String track, String sourcePath) {
        if ("SOAP".equalsIgnoreCase(track)) {
            // GenerationService writes via Files.createTempDirectory
            // with prefix "atlas-gen-<projectId>-", so it lands at
            // /tmp/atlas-gen-<projectId>-XXX (random suffix). Glob
            // /tmp for the latest match for this projectId.
            try {
                Path tmpRoot = Path.of("/tmp");
                String prefix = "atlas-gen-" + projectId + "-";
                Path latest = null;
                long latestMs = -1;
                try (var stream = Files.list(tmpRoot)) {
                    for (Path p : (Iterable<Path>) stream::iterator) {
                        String name = p.getFileName().toString();
                        if (!name.startsWith(prefix)) continue;
                        Path srcDir = p.resolve("src");
                        if (!Files.isDirectory(srcDir)) continue;
                        long mtime = Files.getLastModifiedTime(p).toMillis();
                        if (mtime > latestMs) {
                            latestMs = mtime;
                            latest = srcDir;
                        }
                    }
                }
                if (latest != null) return latest;
            } catch (Exception ignored) {}
            // Fallback to the configured root (won't exist by default
            // but lets the error message surface a sensible path).
            return Path.of(genWorkRoot, projectId.toString(), "src");
        }
        // UPLIFT: the customer's modified source tree.
        if (sourcePath == null || sourcePath.isBlank()) {
            throw new IllegalStateException("UPLIFT build needs a sourcePath");
        }
        return Path.of(sourcePath);
    }

    private Map<String, Object> materialise(UUID runId, BuildTestRunner.Result r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId",         runId);
        out.put("status",        r.status);
        out.put("track",         r.track);
        out.put("filesCompiled", r.filesCompiled);
        out.put("compileErrors", r.compileErrors);
        out.put("testsTotal",    r.testsTotal);
        out.put("testsPassed",   r.testsPassed);
        out.put("testsFailed",   r.testsFailed);
        out.put("testsSkipped",  r.testsSkipped);
        out.put("durationMs",    r.durationMs);
        out.put("failures",      r.failures);
        return out;
    }
}
