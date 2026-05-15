package com.envestnet.atlas.reports.web;

import com.envestnet.atlas.reports.service.ReportsService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/internal/reports")
public class ReportsController {

    private final ReportsService svc;

    public ReportsController(ReportsService svc) { this.svc = svc; }

    @PostMapping("/projects/{pid}/build")
    public ResponseEntity<Map<String, Object>> build(@PathVariable UUID pid) throws Exception {
        var row = svc.build(pid);
        return ResponseEntity.ok(Map.of(
                "bundleId", row.id(),
                "status", row.status(),
                "fileCount", row.fileCount() == null ? 0 : row.fileCount(),
                "sizeBytes", row.sizeBytes() == null ? 0 : row.sizeBytes(),
                "closureStub", row.closureStub() == null ? false : row.closureStub()
        ));
    }

    @GetMapping("/projects/{pid}/status")
    public Map<String, Object> status(@PathVariable UUID pid) {
        return svc.status(pid);
    }

    @GetMapping(value = "/projects/{pid}/closure", produces = "text/markdown")
    public ResponseEntity<String> closure(@PathVariable UUID pid) {
        String md = svc.closure(pid);
        if (md == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(md);
    }

    @GetMapping("/projects/{pid}/bundle.zip")
    public ResponseEntity<byte[]> bundle(@PathVariable UUID pid) {
        byte[] zip = svc.download(pid);
        if (zip == null) return ResponseEntity.notFound().build();
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.parseMediaType("application/zip"));
        h.setContentDispositionFormData("attachment", "migration-package.zip");
        return new ResponseEntity<>(zip, h, 200);
    }
}
