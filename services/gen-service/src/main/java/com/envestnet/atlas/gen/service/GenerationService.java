package com.envestnet.atlas.gen.service;

import com.envestnet.atlas.gen.bindings.BindingsGenerator;
import com.envestnet.atlas.gen.domain.GenRunRow;
import com.envestnet.atlas.gen.domain.OutputFileRow;
import com.envestnet.atlas.gen.generator.WsImportRunner;
import com.envestnet.atlas.gen.prov.ProvenanceEmitter;
import com.envestnet.atlas.gen.repo.GenRunRepository;
import com.envestnet.atlas.gen.repo.OutputFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class GenerationService {
    private static final Logger log = LoggerFactory.getLogger(GenerationService.class);

    private final GenRunRepository runs;
    private final OutputFileRepository files;
    private final BindingsGenerator bindingsGen;
    private final S3Client s3;
    private final JdbcTemplate jdbc;
    private final ProvenanceEmitter prov;
    private final String artifactBucket;

    public GenerationService(GenRunRepository runs,
                             OutputFileRepository files,
                             BindingsGenerator bindingsGen,
                             S3Client s3,
                             JdbcTemplate jdbc,
                             ProvenanceEmitter prov,
                             @Value("${MINIO_BUCKET_ARTIFACTS:atlas-artifacts}") String artifactBucket) {
        this.runs = runs;
        this.files = files;
        this.bindingsGen = bindingsGen;
        this.s3 = s3;
        this.jdbc = jdbc;
        this.prov = prov;
        this.artifactBucket = artifactBucket;
    }

    public GenRunRow run(UUID projectId, String basePackage) throws Exception {
        log.info("Generation run starting · project={} pkg={}", projectId, basePackage);
        GenRunRow run = runs.save(new GenRunRow(
                null, projectId, OffsetDateTime.now(), null,
                "running", null, null, null, null, 0, 0, null, "{}"
        ));

        Path workDir = Files.createTempDirectory("atlas-gen-" + projectId + "-");
        try {
            // 1. Fetch A-WSDL from MinIO.
            String wsdlKey = projectId + "/authoritative.wsdl";
            byte[] wsdlBytes;
            try (ResponseInputStream<GetObjectResponse> in = s3.getObject(GetObjectRequest.builder()
                    .bucket(artifactBucket).key(wsdlKey).build())) {
                wsdlBytes = in.readAllBytes();
            } catch (Exception e) {
                fail(run.id(), "no Authoritative WSDL — finalise reconciliation first");
                throw new IllegalStateException("Authoritative WSDL not found", e);
            }
            Path wsdlFile = workDir.resolve("authoritative.wsdl");
            Files.write(wsdlFile, wsdlBytes);
            String aWsdlUri = "s3://" + artifactBucket + "/" + wsdlKey;

            // 2. Generate bindings file.
            String bindingsXml = bindingsGen.generate(projectId, basePackage);
            Path bindingsFile = workDir.resolve("bindings.xjb");
            Files.writeString(bindingsFile, bindingsXml);
            String bindingsKey = projectId + "/bindings.xjb";
            s3.putObject(PutObjectRequest.builder()
                            .bucket(artifactBucket).key(bindingsKey)
                            .contentType("application/xml").build(),
                    RequestBody.fromBytes(bindingsXml.getBytes(StandardCharsets.UTF_8)));
            String bindingsUri = "s3://" + artifactBucket + "/" + bindingsKey;

            // 3. Run wsimport.
            WsImportRunner.Result result;
            try {
                result = new WsImportRunner().run(workDir, wsdlFile, bindingsFile, basePackage);
            } catch (Exception e) {
                fail(run.id(), "wsimport threw: " + e.getMessage());
                throw e;
            }

            // 4. ZIP the generated source and upload.
            String zipKey = projectId + "/jaxws-source.zip";
            byte[] zipBytes = zipDir(result.sourceDir());
            s3.putObject(PutObjectRequest.builder()
                            .bucket(artifactBucket).key(zipKey)
                            .contentType("application/zip").build(),
                    RequestBody.fromBytes(zipBytes));
            String outputUri = "s3://" + artifactBucket + "/" + zipKey;

            // 5. Per-file index for the Output Tree pane.
            files.deleteByRun(run.id());
            for (WsImportRunner.GeneratedFile f : result.files()) {
                files.save(new OutputFileRow(null, run.id(), projectId,
                        f.path(), f.sizeBytes(), null));
            }

            // 6. Final summary update.
            String summary = String.format(
                    "{\"files\":%d,\"errors\":%d,\"warnings\":%d,\"package\":\"%s\",\"zip_bytes\":%d}",
                    result.files().size(), result.errors(), result.warnings(),
                    basePackage, zipBytes.length);
            jdbc.update("""
                UPDATE gen.run SET status=?, finished_at=now(),
                  a_wsdl_uri=?, bindings_uri=?, output_uri=?,
                  file_count=?, error_count=?, warning_count=?,
                  log_text=?, summary=?::jsonb WHERE id=?
                """,
                result.ok() ? "completed" : "failed",
                aWsdlUri, bindingsUri, outputUri,
                result.files().size(), result.errors(), result.warnings(),
                result.logText(), summary, run.id());

            // Provenance
            ProvenanceEmitter.Event ev = ProvenanceEmitter.Event.of(
                    projectId, "system", "gen-service", "generation_completed");
            ev.output = Map.of(
                    "files", result.files().size(),
                    "errors", result.errors(),
                    "warnings", result.warnings(),
                    "package", basePackage);
            ev.links = List.of("run:" + run.id(), outputUri);
            prov.emit(ev);

            return runs.findById(run.id()).orElse(run);
        } finally {
            try { deleteTree(workDir); } catch (Exception ignored) {}
        }
    }

    public Map<String, Object> status(UUID projectId) {
        Optional<GenRunRow> r = runs.latest(projectId);
        Map<String, Object> out = new LinkedHashMap<>();
        if (r.isEmpty()) {
            out.put("run", null);
            out.put("files", List.of());
            return out;
        }
        GenRunRow row = r.get();
        out.put("run", runMap(row));
        out.put("files", files.findByRun(row.id()).stream().map(this::fileMap).toList());
        return out;
    }

    public byte[] downloadZip(UUID projectId) {
        Optional<GenRunRow> r = runs.latest(projectId);
        if (r.isEmpty() || r.get().outputUri() == null) return null;
        String key = projectId + "/jaxws-source.zip";
        try (ResponseInputStream<GetObjectResponse> in = s3.getObject(GetObjectRequest.builder()
                .bucket(artifactBucket).key(key).build())) {
            return in.readAllBytes();
        } catch (Exception e) {
            log.warn("zip download failed: {}", e.toString());
            return null;
        }
    }

    public String fetchBindings(UUID projectId) {
        String key = projectId + "/bindings.xjb";
        try (ResponseInputStream<GetObjectResponse> in = s3.getObject(GetObjectRequest.builder()
                .bucket(artifactBucket).key(key).build())) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    public String fetchFile(UUID runId, String path) {
        Optional<GenRunRow> r = runs.findById(runId);
        if (r.isEmpty()) return null;
        // Files are inside the project's ZIP — serve from the work archive.
        // For Phase 1d we return null and let the UI show only metadata; viewing
        // the file body is a Phase-1e enhancement.
        return null;
    }

    /* ---------------- helpers ---------------- */

    private byte[] zipDir(Path root) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            if (Files.isDirectory(root)) {
                Files.walk(root).filter(Files::isRegularFile).forEach(p -> {
                    try {
                        String entry = root.relativize(p).toString().replace('\\', '/');
                        zos.putNextEntry(new ZipEntry(entry));
                        Files.copy(p, zos);
                        zos.closeEntry();
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
            }
        }
        return baos.toByteArray();
    }

    private void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        Files.walk(root).sorted(Comparator.reverseOrder()).forEach(p -> {
            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
        });
    }

    private void fail(UUID runId, String message) {
        jdbc.update("UPDATE gen.run SET status='failed', finished_at=now(), log_text=? WHERE id=?",
                message, runId);
    }

    private Map<String, Object> runMap(GenRunRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("startedAt", r.startedAt());
        m.put("finishedAt", r.finishedAt());
        m.put("status", r.status());
        m.put("aWsdlUri", r.aWsdlUri());
        m.put("bindingsUri", r.bindingsUri());
        m.put("outputUri", r.outputUri());
        m.put("fileCount", r.fileCount());
        m.put("errorCount", r.errorCount());
        m.put("warningCount", r.warningCount());
        m.put("logText", r.logText());
        return m;
    }

    private Map<String, Object> fileMap(OutputFileRow f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", f.path());
        m.put("sizeBytes", f.sizeBytes());
        return m;
    }
}
