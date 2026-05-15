package com.envestnet.atlas.recon.prov;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Fire-and-forget client for prov-service. Identical semantics across services
 * — kept as a per-service copy because @Async and Spring Boot project-local
 * config makes a shared library awkward.
 */
@Component
public class ProvenanceEmitter {
    private static final Logger log = LoggerFactory.getLogger(ProvenanceEmitter.class);

    private final RestTemplate http;
    private final String provUrl;
    private final boolean enabled;

    public ProvenanceEmitter(RestTemplate http,
                             @Value("${PROV_SERVICE_URL:http://prov-service:8091}") String provUrl,
                             @Value("${PROV_ENABLED:true}") boolean enabled) {
        this.http = http;
        this.provUrl = provUrl;
        this.enabled = enabled;
    }

    @Async
    public void emit(Event e) {
        if (!enabled) return;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("projectId", e.projectId);
            body.put("actorKind", e.actorKind);
            body.put("actorId", e.actorId);
            body.put("action", e.action);
            if (e.prompt != null)     body.put("prompt", e.prompt);
            if (e.model != null)      body.put("model", e.model);
            if (e.output != null)     body.put("output", e.output);
            if (e.tokensIn != null)   body.put("tokensIn", e.tokensIn);
            if (e.tokensOut != null)  body.put("tokensOut", e.tokensOut);
            if (e.latencyMs != null)  body.put("latencyMs", e.latencyMs);
            if (e.costUsd != null)    body.put("costUsd", e.costUsd);
            if (e.links != null)      body.put("links", e.links);
            if (e.humanReview != null) body.put("humanReview", e.humanReview);
            // Phase 2L cost attribution — see arch-service's emitter for the
            // full rationale. Forward workspace + user from MDC when the
            // caller didn't set them explicitly.
            UUID workspaceId = e.workspaceId != null ? e.workspaceId : parseUuid(org.slf4j.MDC.get("workspaceId"));
            String userEmail = (e.userEmail != null && !e.userEmail.isBlank())
                    ? e.userEmail : org.slf4j.MDC.get("userEmail");
            if (workspaceId != null) body.put("workspaceId", workspaceId);
            if (userEmail   != null) body.put("userEmail",   userEmail);
            http.postForObject(provUrl + "/internal/prov/emit", body, Map.class);
        } catch (Exception ex) {
            log.debug("prov emit failed (swallowed): {}", ex.toString());
        }
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s.trim()); }
        catch (IllegalArgumentException ex) { return null; }
    }

    public static class Event {
        public UUID projectId;
        public String actorKind = "system";
        public String actorId   = "atlas";
        public String action;
        public Object prompt;
        public String model;
        public Object output;
        public Integer tokensIn;
        public Integer tokensOut;
        public Double costUsd;
        public Integer latencyMs;
        public List<String> links;
        public Object humanReview;
        public UUID workspaceId;
        public String userEmail;

        public static Event of(UUID pid, String actorKind, String actorId, String action) {
            Event e = new Event();
            e.projectId = pid; e.actorKind = actorKind; e.actorId = actorId; e.action = action;
            return e;
        }
    }
}
