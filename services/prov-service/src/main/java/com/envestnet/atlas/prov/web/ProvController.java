package com.envestnet.atlas.prov.web;

import com.envestnet.atlas.prov.service.ProvService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/internal/prov")
public class ProvController {

    private final ProvService svc;

    public ProvController(ProvService svc) { this.svc = svc; }

    @PostMapping("/emit")
    public Map<String, Object> emit(@RequestBody ProvService.EmitRequest req) {
        UUID id = svc.emit(req);
        return Map.of("ok", true, "id", id.toString());
    }

    @GetMapping("/projects/{pid}")
    public List<Map<String, Object>> query(@PathVariable UUID pid,
                                           @RequestParam(required = false) String action,
                                           @RequestParam(required = false) String actorKind,
                                           @RequestParam(required = false) String actorId,
                                           @RequestParam(required = false) String q,
                                           @RequestParam(defaultValue = "200") int limit) {
        ProvService.Filter f = new ProvService.Filter();
        f.action = action;
        f.actorKind = actorKind;
        f.actorId = actorId;
        f.q = q;
        return svc.query(pid, f, limit);
    }

    @GetMapping("/projects/{pid}/aggregate")
    public Map<String, Object> aggregate(@PathVariable UUID pid) {
        return svc.aggregate(pid);
    }

    @GetMapping(value = "/projects/{pid}/export.csv", produces = "text/csv")
    public ResponseEntity<byte[]> exportCsv(@PathVariable UUID pid) {
        byte[] body = svc.exportCsv(pid);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.parseMediaType("text/csv"));
        h.setContentDispositionFormData("attachment", "provenance.csv");
        return new ResponseEntity<>(body, h, 200);
    }

    /**
     * Per-workspace cost rollup (Phase 2L). Returns totals + breakdowns
     * by user, project, model, and a daily sparkline for the time
     * window {@code [from, to)}. Default window is the trailing 30
     * days from now.
     *
     * <p>This endpoint is internal — prj-service relays it under
     * {@code /api/v1/workspaces/{ws}/cost} after a workspace-access
     * check so non-members can't read another tenant's spend.</p>
     */
    @GetMapping("/workspaces/{wsId}/cost")
    public Map<String, Object> workspaceCost(
            @PathVariable UUID wsId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        // Default window: trailing 30 days, UTC-aligned to midnight so
        // the daily buckets in the response are stable across requests
        // within the same day.
        OffsetDateTime toTs = parseTimestampOr(to,
                OffsetDateTime.now(ZoneOffset.UTC)
                        .withHour(0).withMinute(0).withSecond(0).withNano(0)
                        .plusDays(1));
        OffsetDateTime fromTs = parseTimestampOr(from, toTs.minusDays(30));
        return svc.workspaceCost(wsId, fromTs, toTs);
    }

    private static OffsetDateTime parseTimestampOr(String s, OffsetDateTime fallback) {
        if (s == null || s.isBlank()) return fallback;
        try { return OffsetDateTime.parse(s); }
        catch (DateTimeParseException e) { return fallback; }
    }
}
