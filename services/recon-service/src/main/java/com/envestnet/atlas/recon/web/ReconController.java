package com.envestnet.atlas.recon.web;

import com.envestnet.atlas.recon.service.ReconciliationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/internal/recon")
public class ReconController {

    private final ReconciliationService svc;

    public ReconController(ReconciliationService svc) { this.svc = svc; }

    @PostMapping("/projects/{pid}/run")
    public ResponseEntity<Map<String, Object>> run(@PathVariable UUID pid,
                                                   @RequestBody RunRequest req) throws Exception {
        var r = svc.run(pid, req.sourcePath());
        return ResponseEntity.ok(Map.of(
                "runId", r.id(),
                "status", r.status(),
                "startedAt", r.startedAt(),
                "finishedAt", r.finishedAt()
        ));
    }

    @GetMapping("/projects/{pid}/status")
    public Map<String, Object> status(@PathVariable UUID pid) {
        return svc.status(pid);
    }

    @PostMapping("/decisions/{did}/resolve")
    public Map<String, Object> resolve(@PathVariable UUID did,
                                       @RequestBody ReconciliationService.ResolveRequest body) {
        return svc.resolve(did, body);
    }

    /**
     * Single-decision lookup. Used by prj-service to read kind/path
     * /chosenAction after a resolve so it can record the decision in
     * the cross-project pattern library.
     */
    @GetMapping("/decisions/{did}")
    public ResponseEntity<Map<String, Object>> getDecision(@PathVariable UUID did) {
        Map<String, Object> dec = svc.getDecision(did);
        if (dec == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(dec);
    }

    @GetMapping(value = "/projects/{pid}/wsdl/authoritative", produces = "application/xml")
    public ResponseEntity<String> authoritativeWsdl(@PathVariable UUID pid) {
        String wsdl = svc.fetchAuthoritativeWsdl(pid);
        if (wsdl == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(wsdl);
    }

    @PostMapping("/projects/{pid}/wsdl/synth")
    public Map<String, Object> synthNow(@PathVariable UUID pid) {
        String uri = svc.synthesiseAuthoritativeWsdl(pid);
        return Map.of("ok", uri != null, "uri", uri == null ? "" : uri);
    }

    public record RunRequest(String sourcePath) {}
}
