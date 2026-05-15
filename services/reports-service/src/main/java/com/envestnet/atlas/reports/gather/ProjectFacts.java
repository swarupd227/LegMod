package com.envestnet.atlas.reports.gather;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Pulls a snapshot of every stage's data for the project so the closure-doc
 * generator and bundle assembler share one truth. Anything missing degrades
 * gracefully (empty list / null map) so a partially-complete project still
 * yields a coherent — if smaller — bundle.
 */
@Component
public class ProjectFacts {
    private static final Logger log = LoggerFactory.getLogger(ProjectFacts.class);

    private final RestTemplate http;
    private final String prjUrl;
    private final String archUrl;
    private final String capUrl;
    private final String reconUrl;
    private final String genUrl;
    private final String diffUrl;

    public ProjectFacts(RestTemplate http,
                        @Value("${PRJ_SERVICE_URL:http://prj-service:8081}") String prjUrl,
                        @Value("${ARCH_SERVICE_URL:http://arch-service:8083}") String archUrl,
                        @Value("${CAP_SERVICE_URL:http://cap-service:8085}") String capUrl,
                        @Value("${RECON_SERVICE_URL:http://recon-service:8086}") String reconUrl,
                        @Value("${GEN_SERVICE_URL:http://gen-service:8087}") String genUrl,
                        @Value("${DIFF_SERVICE_URL:http://diff-service:8089}") String diffUrl) {
        this.http = http;
        this.prjUrl = prjUrl;
        this.archUrl = archUrl;
        this.capUrl = capUrl;
        this.reconUrl = reconUrl;
        this.genUrl = genUrl;
        this.diffUrl = diffUrl;
    }

    public Snapshot gather(UUID projectId) {
        Snapshot s = new Snapshot();
        s.projectId = projectId;
        s.project   = getMap(prjUrl  + "/api/v1/projects/" + projectId);
        s.operations = getList(archUrl + "/internal/archaeology/operations/" + projectId);
        s.adapters   = getList(archUrl + "/internal/archaeology/adapters/" + projectId);
        s.captureStatus = getMap(capUrl + "/internal/capture/projects/" + projectId + "/status");
        s.reconStatus   = getMap(reconUrl + "/internal/recon/projects/" + projectId + "/status");
        s.genStatus     = getMap(genUrl + "/internal/generation/projects/" + projectId + "/status");
        s.diffStatus    = getMap(diffUrl + "/internal/diff/projects/" + projectId + "/status");
        return s;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getMap(String url) {
        try { return (Map<String, Object>) http.getForObject(url, Map.class); }
        catch (Exception e) { log.warn("fetch failed {} : {}", url, e.toString()); return Map.of(); }
    }

    private List<?> getList(String url) {
        try {
            Object[] arr = http.getForObject(url, Object[].class);
            return arr == null ? List.of() : Arrays.asList(arr);
        } catch (Exception e) { log.warn("fetch failed {} : {}", url, e.toString()); return List.of(); }
    }

    public static class Snapshot {
        public UUID projectId;
        public Map<String, Object> project       = Map.of();
        public List<?>             operations    = List.of();
        public List<?>             adapters      = List.of();
        public Map<String, Object> captureStatus = Map.of();
        public Map<String, Object> reconStatus   = Map.of();
        public Map<String, Object> genStatus     = Map.of();
        public Map<String, Object> diffStatus    = Map.of();
    }
}
