package com.envestnet.atlas.uplift.service;

import com.envestnet.atlas.uplift.domain.MigrationChangeRow;
import com.envestnet.atlas.uplift.domain.MigrationRunRow;
import com.envestnet.atlas.uplift.domain.ModuleRow;
import com.envestnet.atlas.uplift.domain.RecipeRow;
import com.envestnet.atlas.uplift.domain.StranglerStepRow;
import com.envestnet.atlas.uplift.prov.ProvenanceEmitter;
import com.envestnet.atlas.uplift.repo.MigrationChangeRepository;
import com.envestnet.atlas.uplift.repo.MigrationRunRepository;
import com.envestnet.atlas.uplift.repo.ModuleRepository;
import com.envestnet.atlas.uplift.repo.RecipeRepository;
import com.envestnet.atlas.uplift.repo.ScanRunRepository;
import com.envestnet.atlas.uplift.repo.StranglerStepRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Stream;

/**
 * Executes a strangler step: scan the legacy module's source files, apply each
 * accepted recipe in sequence, persist a run with per-file diffs, and write the
 * transformed bundle to MinIO. Synchronous — small modules in the demo finish
 * in well under a second.
 */
@Service
public class MigrationService {
    private static final Logger log = LoggerFactory.getLogger(MigrationService.class);

    private final StranglerStepRepository steps;
    private final RecipeRepository recipes;
    private final ModuleRepository modules;
    private final ScanRunRepository runs;
    private final MigrationRunRepository migrationRuns;
    private final MigrationChangeRepository migrationChanges;
    private final ProvenanceEmitter prov;
    private final JdbcTemplate jdbc;
    private final S3Client s3;
    private final String bucket;

    public MigrationService(StranglerStepRepository steps,
                            RecipeRepository recipes,
                            ModuleRepository modules,
                            ScanRunRepository runs,
                            MigrationRunRepository migrationRuns,
                            MigrationChangeRepository migrationChanges,
                            ProvenanceEmitter prov,
                            JdbcTemplate jdbc,
                            S3Client s3,
                            @Value("${MINIO_BUCKET_CORPUS:atlas-corpus}") String bucket) {
        this.steps = steps;
        this.recipes = recipes;
        this.modules = modules;
        this.runs = runs;
        this.migrationRuns = migrationRuns;
        this.migrationChanges = migrationChanges;
        this.prov = prov;
        this.jdbc = jdbc;
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Transactional
    public MigrationRunRow run(UUID projectId, UUID stepId) {
        StranglerStepRow step = steps.findById(stepId)
                .orElseThrow(() -> new IllegalArgumentException("step not found"));
        if (!step.projectId().equals(projectId)) {
            throw new IllegalArgumentException("step belongs to a different project");
        }
        if (step.moduleId() == null) {
            throw new IllegalArgumentException("step has no module attached");
        }
        ModuleRow module = modules.findById(step.moduleId())
                .orElseThrow(() -> new IllegalArgumentException("module not found"));

        // Resolve project source path from the most recent inventory scan.
        Path sourceRoot = runs.latest(projectId)
                .map(r -> Path.of(r.sourcePath()))
                .orElseThrow(() -> new IllegalStateException(
                        "No inventory scan source path on file."));

        // Resolve recipes attached to this step.
        List<UUID> recipeIds = parseUuidList(step.recipeIds());
        List<RecipeRow> stepRecipes = recipeIds.stream()
                .map(recipes::findById)
                .filter(Optional::isPresent).map(Optional::get)
                .toList();
        if (stepRecipes.isEmpty()) {
            throw new IllegalStateException("no recipes attached to this step");
        }

        // Insert the run row and capture the generated id.
        UUID runId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO uplift.migration_run (id, project_id, step_id, module_id, status)
            VALUES (?, ?, ?, ?, 'running')
            """, runId, projectId, stepId, module.id());

        try {
            ExecResult res = applyToModule(sourceRoot, module, stepRecipes, projectId, stepId, runId);

            String summaryJson = String.format(
                "{\"filesScanned\":%d,\"filesChanged\":%d,\"totalChanges\":%d,\"recipesApplied\":%d,\"byRecipe\":%s}",
                res.filesScanned, res.filesChanged, res.totalChanges, stepRecipes.size(),
                jsonByRecipe(res.byRecipe));

            jdbc.update("""
                UPDATE uplift.migration_run
                   SET status = 'completed',
                       finished_at = now(),
                       summary = ?::jsonb,
                       output_uri = ?
                 WHERE id = ?
                """, summaryJson, res.outputUri, runId);

            // Mark the strangler step as ready once a successful migration lands.
            if (!"extracted".equals(step.status())) {
                jdbc.update("""
                    UPDATE uplift.strangler_step
                       SET status = 'ready', updated_at = now()
                     WHERE id = ? AND status = 'planned'
                    """, stepId);
            }

            ProvenanceEmitter.Event ev = ProvenanceEmitter.Event.of(
                    projectId, "system", "uplift-service", "module_migrated");
            ev.output = Map.of(
                    "stepId", stepId.toString(),
                    "module", module.name(),
                    "filesChanged", res.filesChanged,
                    "totalChanges", res.totalChanges);
            ev.links = List.of("migration_run:" + runId, "strangler_step:" + stepId);
            prov.emit(ev);

            log.info("Module migration done · project={} module={} files={} changes={}",
                    projectId, module.name(), res.filesChanged, res.totalChanges);

        } catch (Exception e) {
            jdbc.update("""
                UPDATE uplift.migration_run
                   SET status = 'failed', finished_at = now(), error_text = ?
                 WHERE id = ?
                """, e.getMessage(), runId);
            log.warn("Module migration failed · project={} step={} err={}", projectId, stepId, e.getMessage());
            throw new RuntimeException("migration failed: " + e.getMessage(), e);
        }

        return migrationRuns.findById(runId).orElseThrow();
    }

    public Map<String, Object> statusForStep(UUID projectId, UUID stepId) {
        Optional<MigrationRunRow> latest = migrationRuns.latestForStep(stepId);
        Map<String, Object> out = new LinkedHashMap<>();
        if (latest.isEmpty()) {
            out.put("run", null);
            out.put("changes", List.of());
            return out;
        }
        MigrationRunRow r = latest.get();
        out.put("run", runMap(r));
        out.put("changes", migrationChanges.findByRun(r.id()).stream()
                .map(this::changeMap).toList());
        return out;
    }

    public Map<String, Object> projectStatus(UUID projectId) {
        List<MigrationRunRow> rows = migrationRuns.findByProject(projectId);
        long completed = rows.stream().filter(r -> "completed".equals(r.status())).count();
        long failed    = rows.stream().filter(r -> "failed".equals(r.status())).count();
        long running   = rows.stream().filter(r -> "running".equals(r.status())).count();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runs", rows.stream().map(this::runMap).toList());
        out.put("counts", Map.of("total", rows.size(),
                "completed", completed, "failed", failed, "running", running));
        out.put("readyForGate", completed >= 1);
        return out;
    }

    public Optional<MigrationChangeRow> changeFor(UUID runId, String filePath) {
        return migrationChanges.findByRunAndPath(runId, filePath);
    }

    /* ---------------- core migration loop ---------------- */

    private static class ExecResult {
        int filesScanned, filesChanged, totalChanges;
        Map<String, Integer> byRecipe = new LinkedHashMap<>();
        String outputUri;
    }

    private ExecResult applyToModule(Path sourceRoot, ModuleRow module,
                                     List<RecipeRow> stepRecipes,
                                     UUID projectId, UUID stepId, UUID runId) throws IOException {
        ExecResult res = new ExecResult();
        if (!Files.isDirectory(sourceRoot)) {
            throw new IOException("source path missing: " + sourceRoot);
        }

        // Walk files whose package declaration starts with the module's package.
        // Stage A scanner keys modules by *leaf* package name, but the package
        // value stored is the full FQN — we match on prefix to catch sub-packages.
        String pkgPrefix = module.packageName();
        List<Path> javaFiles;
        try (Stream<Path> s = Files.walk(sourceRoot)) {
            javaFiles = s.filter(p -> p.toString().endsWith(".java")).toList();
        }

        for (Path p : javaFiles) {
            String body;
            try { body = Files.readString(p); } catch (IOException ignored) { continue; }
            String pkg = packageOf(body);
            if (pkg == null) continue;
            if (!matchesModule(pkg, pkgPrefix, module.name())) continue;
            res.filesScanned++;

            String working = body;
            int fileChanges = 0;
            Map<String, Integer> fileByRecipe = new LinkedHashMap<>();
            for (RecipeRow r : stepRecipes) {
                RecipeTransforms.Result transformed = RecipeTransforms.apply(r.recipeId(), working);
                if (transformed.changes() > 0) {
                    working = transformed.text();
                    fileChanges += transformed.changes();
                    fileByRecipe.merge(r.recipeId(), transformed.changes(), Integer::sum);
                    res.byRecipe.merge(r.recipeId(), transformed.changes(), Integer::sum);
                }
            }
            if (fileChanges == 0) continue;

            res.filesChanged++;
            res.totalChanges += fileChanges;
            String relPath = sourceRoot.relativize(p).toString().replace('\\', '/');
            String diff = UnifiedDiff.generate(relPath, body, working);
            // Truncate very large diffs to keep DB rows manageable.
            String diffTrimmed = diff.length() > 200_000 ? diff.substring(0, 200_000) + "\n... (truncated) ...\n" : diff;

            // We attribute the row to the recipe with the largest contribution
            // for this file, but the diff itself reflects all recipes combined.
            String primary = fileByRecipe.entrySet().stream()
                    .max(Comparator.comparingInt(Map.Entry::getValue))
                    .map(Map.Entry::getKey).orElse("(unattributed)");

            jdbc.update("""
                INSERT INTO uplift.migration_change (run_id, file_path, recipe_id, changes, diff_text)
                VALUES (?, ?, ?, ?, ?)
                """, runId, relPath, primary, fileChanges, diffTrimmed);

            // Push the transformed file to MinIO so we have the bundle on disk.
            String key = String.format("uplift/%s/migrations/%s/%s", projectId, runId, relPath);
            try {
                s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key)
                                .contentType("text/x-java-source").build(),
                        RequestBody.fromString(working, StandardCharsets.UTF_8));
            } catch (Exception e) {
                log.warn("MinIO upload failed (continuing) · key={} err={}", key, e.getMessage());
            }

            // Also write back to the cloned source tree on disk so the Stage
            // F Build & Test gate can validate the change against the real
            // file system. `/uploads/<projectId>/` is a local clone we own;
            // we're not touching the customer's repo, so in-place mutation
            // is safe.
            try {
                Files.writeString(p, working, StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warn("Source write-back failed (continuing) · file={} err={}",
                        p, e.getMessage());
            }
        }

        res.outputUri = String.format("s3://%s/uplift/%s/migrations/%s/", bucket, projectId, runId);
        return res;
    }

    /* ---------------- helpers ---------------- */

    private static String packageOf(String body) {
        for (String line : body.split("\\r?\\n")) {
            String t = line.trim();
            if (t.startsWith("package ") && t.endsWith(";")) {
                return t.substring("package ".length(), t.length() - 1).trim();
            }
            if (!t.isEmpty() && !t.startsWith("//") && !t.startsWith("*")
                    && !t.startsWith("/*") && !t.startsWith("import")) {
                break;
            }
        }
        return null;
    }

    private static boolean matchesModule(String pkg, String modulePackage, String moduleName) {
        if (pkg.equals(modulePackage)) return true;
        if (pkg.startsWith(modulePackage + ".")) return true;
        // Stage A's module name is the leaf segment — match anywhere along the path.
        if (pkg.endsWith("." + moduleName) || pkg.contains("." + moduleName + ".")) return true;
        return false;
    }

    private static String jsonByRecipe(Map<String, Integer> byRecipe) {
        StringBuilder sb = new StringBuilder("{");
        int i = 0;
        for (var e : byRecipe.entrySet()) {
            if (i++ > 0) sb.append(',');
            sb.append('"').append(e.getKey().replace("\"", "\\\"")).append('"')
              .append(':').append(e.getValue());
        }
        sb.append('}');
        return sb.toString();
    }

    private Map<String, Object> runMap(MigrationRunRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("stepId", r.stepId());
        m.put("moduleId", r.moduleId());
        m.put("startedAt", r.startedAt());
        m.put("finishedAt", r.finishedAt());
        m.put("status", r.status());
        m.put("summary", r.summary() == null ? "{}" : r.summary());
        m.put("outputUri", r.outputUri());
        m.put("errorText", r.errorText());
        return m;
    }

    private Map<String, Object> changeMap(MigrationChangeRow c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id());
        m.put("filePath", c.filePath());
        m.put("recipeId", c.recipeId());
        m.put("changes", c.changes());
        return m;
    }

    private static List<UUID> parseUuidList(String json) {
        if (json == null || json.isBlank()) return List.of();
        List<UUID> out = new ArrayList<>();
        for (String tok : json.replaceAll("[\\[\\]\\s]", "").split(",")) {
            String s = tok.replace("\"", "");
            if (!s.isEmpty()) {
                try { out.add(UUID.fromString(s)); } catch (Exception ignored) {}
            }
        }
        return out;
    }
}
