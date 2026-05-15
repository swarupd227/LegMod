package com.envestnet.atlas.diff.web;

import com.envestnet.atlas.diff.service.DifferentialService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/internal/diff")
public class DiffController {

    private final DifferentialService svc;

    public DiffController(DifferentialService svc) { this.svc = svc; }

    @PostMapping("/projects/{pid}/run")
    public ResponseEntity<Map<String, Object>> run(@PathVariable UUID pid,
                                                   @RequestBody(required = false) RunRequest req)
            throws Exception {
        int sample = req == null || req.sampleSize() == null ? 250 : req.sampleSize();
        var r = svc.run(pid, Math.max(1, Math.min(2000, sample)));
        return ResponseEntity.ok(Map.of(
                "runId", r.id(),
                "status", r.status(),
                "envelopesReplayed", r.envelopesReplayed() == null ? 0 : r.envelopesReplayed(),
                "passCount", r.passCount() == null ? 0 : r.passCount(),
                "benignCount", r.benignCount() == null ? 0 : r.benignCount(),
                "amberCount", r.amberCount() == null ? 0 : r.amberCount(),
                "redCount", r.redCount() == null ? 0 : r.redCount()
        ));
    }

    @GetMapping("/projects/{pid}/status")
    public Map<String, Object> status(@PathVariable UUID pid) {
        return svc.status(pid);
    }

    @PostMapping("/divergences/{did}/auto-fix")
    public Map<String, Object> autoFix(@PathVariable UUID did,
                                       @RequestBody(required = false) AutoFixRequest req) {
        return svc.autoFix(did,
                req == null || req.user() == null ? "system" : req.user());
    }

    public record RunRequest(Integer sampleSize) {}
    public record AutoFixRequest(String user) {}
}
