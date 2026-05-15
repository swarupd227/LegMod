package com.envestnet.atlas.gen.web;

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

    public GenController(GenerationService svc) { this.svc = svc; }

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

    public record RunRequest(String basePackage) {}
}
