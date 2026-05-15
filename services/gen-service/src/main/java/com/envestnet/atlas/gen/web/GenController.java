package com.envestnet.atlas.gen.web;

import com.envestnet.atlas.gen.service.BuildTestService;
import com.envestnet.atlas.gen.service.GenerationService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/internal/generation")
public class GenController {

    private final GenerationService svc;
    private final BuildTestService buildSvc;

    public GenController(GenerationService svc, BuildTestService buildSvc) {
        this.svc = svc;
        this.buildSvc = buildSvc;
    }

    @PostMapping("/projects/{pid}/run")
    public ResponseEntity<Map<String, Object>> run(@PathVariable UUID pid,
                                                   @RequestBody(required = false) RunRequest req)
            throws Exception {
        String pkg = req != null && req.basePackage() != null && !req.basePackage().isBlank()
                ? req.basePackage() : "com.envestnet.broadridge";
        var r = svc.run(pid, pkg);
        return ResponseEntity.ok(Map.of(
                "runId", r.id(),
                "status", r.status(),
                "fileCount", r.fileCount() == null ? 0 : r.fileCount(),
                "errorCount", r.errorCount() == null ? 0 : r.errorCount(),
                "warningCount", r.warningCount() == null ? 0 : r.warningCount()
        ));
    }

    @GetMapping("/projects/{pid}/status")
    public Map<String, Object> status(@PathVariable UUID pid) {
        return svc.status(pid);
    }

    @GetMapping(value = "/projects/{pid}/bindings", produces = "application/xml")
    public ResponseEntity<String> bindings(@PathVariable UUID pid) {
        String body = svc.fetchBindings(pid);
        if (body == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(body);
    }

    @GetMapping("/projects/{pid}/output.zip")
    public ResponseEntity<byte[]> downloadZip(@PathVariable UUID pid) {
        byte[] zip = svc.downloadZip(pid);
        if (zip == null) return ResponseEntity.notFound().build();
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.parseMediaType("application/zip"));
        h.setContentDispositionFormData("attachment", "jaxws-source.zip");
        return new ResponseEntity<>(zip, h, 200);
    }

    /**
     * Stage F · Build & Test gate. Compiles the migrated/generated
     * code in a sandboxed JVM and runs the project's unit tests. The
     * returned object carries pass/fail counts, compile errors, and
     * the first 20 failing test names so the SPA can render results
     * without re-querying.
     */
    @PostMapping("/projects/{pid}/build")
    public ResponseEntity<Map<String, Object>> build(@PathVariable UUID pid,
                                                     @RequestBody BuildRequest req) {
        String track = (req.track() == null || req.track().isBlank()) ? "UPLIFT" : req.track();
        Map<String, Object> result = buildSvc.run(pid, track, req.sourcePath(), req.basePackage());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/projects/{pid}/build/status")
    public Map<String, Object> buildStatus(@PathVariable UUID pid) {
        return buildSvc.status(pid);
    }

    public record RunRequest(String basePackage) {}
    public record BuildRequest(String track, String sourcePath, String basePackage) {}
}
