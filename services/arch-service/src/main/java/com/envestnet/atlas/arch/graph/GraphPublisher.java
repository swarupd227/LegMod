package com.envestnet.atlas.arch.graph;

import com.envestnet.atlas.arch.analysis.SourceWalker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Pushes a Stage A archaeology result into the knowledge graph via graph-service.
 * Failures are logged and swallowed — the SQL writes have already completed and
 * the graph is a derived index. Phase 1c will move this into Temporal so it can
 * be retried durably.
 */
@Component
public class GraphPublisher {
    private static final Logger log = LoggerFactory.getLogger(GraphPublisher.class);

    private final RestTemplate http;
    private final String graphUrl;

    public GraphPublisher(RestTemplate http,
                          @Value("${GRAPH_SERVICE_URL:http://graph-service:8084}") String graphUrl) {
        this.http = http;
        this.graphUrl = graphUrl;
    }

    public void publish(UUID projectId, SourceWalker.Result result) {
        try {
            // Fresh slate: drop prior project graph nodes, then write everything.
            http.postForObject(graphUrl + "/internal/graph/projects/" + projectId + "/reset",
                    null, Map.class);

            Map<String, Object> payload = buildPayload(result);
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);
            Map<?, ?> resp = http.postForObject(
                    graphUrl + "/internal/graph/projects/" + projectId + "/archaeology",
                    new HttpEntity<>(payload, h), Map.class);
            log.info("graph published · {}", resp);
        } catch (Exception e) {
            log.warn("graph publish failed (project {}): {}", projectId, e.toString());
        }
    }

    private Map<String, Object> buildPayload(SourceWalker.Result result) {
        // Build adapters
        List<Map<String, Object>> adapters = new ArrayList<>();
        for (SourceWalker.Adapter a : result.adapters) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fqn", a.fqn);
            m.put("kind", a.kind);
            m.put("pattern", a.pattern);
            adapters.add(m);
        }

        // Build operations (each carries its own typeMappings list)
        List<Map<String, Object>> operations = new ArrayList<>();
        for (SourceWalker.Operation op : result.operations) {
            Map<String, Object> opMap = new LinkedHashMap<>();
            opMap.put("namespace", op.namespace);
            opMap.put("name", op.name);
            opMap.put("inputType", op.inputType);
            opMap.put("outputType", op.outputType);
            opMap.put("sourceClass", op.sourceClass);
            opMap.put("flags", op.flags);
            opMap.put("confidence", confidenceFor(op));

            List<Map<String, Object>> mappings = new ArrayList<>();
            for (SourceWalker.TypeMapping m : result.typeMappings) {
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("javaType", m.javaType);
                mm.put("qnameNamespace", m.qnameNamespace);
                mm.put("qnameLocal", m.qnameLocal);
                mm.put("adapterFqn", m.adapterFqn);
                mappings.add(mm);
            }
            opMap.put("typeMappings", mappings);
            operations.add(opMap);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operations", operations);
        payload.put("adapters", adapters);
        return payload;
    }

    private String confidenceFor(SourceWalker.Operation op) {
        if (op.flags.isEmpty()) return "high";
        if (op.flags.size() <= 2) return "medium";
        return "low";
    }
}
