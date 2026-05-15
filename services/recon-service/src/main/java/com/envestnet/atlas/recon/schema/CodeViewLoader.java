package com.envestnet.atlas.recon.schema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Pulls the code-derived schema view from arch-service. Each operation has a
 * list of type mappings; we flatten "TypeName.fieldName" by treating the
 * QName local-name as the type, and synthesize a single field row "value"
 * for primitive-styled mappings. (For Phase 1c the merge keys are mostly
 * envelope-driven; the code view contributes adapter + Java-type evidence.)
 */
@Component
public class CodeViewLoader {
    private static final Logger log = LoggerFactory.getLogger(CodeViewLoader.class);

    private final RestTemplate http;
    private final String archUrl;

    public CodeViewLoader(RestTemplate http,
                          @Value("${ARCH_SERVICE_URL:http://arch-service:8083}") String archUrl) {
        this.http = http;
        this.archUrl = archUrl;
    }

    /**
     * Return code-derived ElementInfo keyed by "TypeName.field".
     * The arch-service /internal/archaeology/operations/{pid} response has
     * each operation carrying a list of typeMappings. We fold them into
     * one map per type name. The synthetic field name "_class" represents
     * the class-level QName registration.
     */
    public Map<String, ElementInfo> load(UUID projectId) {
        Map<String, ElementInfo> out = new LinkedHashMap<>();
        try {
            Object[] ops = http.getForObject(
                    archUrl + "/internal/archaeology/operations/" + projectId, Object[].class);
            if (ops == null) return out;

            for (Object raw : ops) {
                if (!(raw instanceof Map<?, ?> m)) continue;
                Object mappings = m.get("typeMappings");
                if (!(mappings instanceof List<?> list)) continue;
                for (Object mm : list) {
                    if (!(mm instanceof Map<?, ?> map)) continue;
                    String javaType = str(map.get("javaType"));
                    String adapter  = str(map.get("adapterFqn"));
                    String localName = str(map.get("qnameLocal"));
                    String namespace = str(map.get("qnameNamespace"));
                    if (localName == null || localName.isEmpty()) continue;

                    String typeName = simple(localName);

                    // Class-level entry — represents that the type exists in code.
                    ElementInfo classInfo = out.computeIfAbsent(typeName + "._class", k -> {
                        ElementInfo i = new ElementInfo();
                        i.present = true;
                        i.namespace = namespace;
                        return i;
                    });
                    classInfo.javaType = javaType;
                    if (adapter != null && !adapter.isBlank()) classInfo.adapter = adapter;

                    // If we know the adapter is a date adapter, project an entry for the
                    // canonical "tradeDate" field on AllocationRequest so the merge engine
                    // can match against the empirical and vendor views.
                    if (adapter != null && adapter.toLowerCase().contains("date")) {
                        if ("AllocationRequest".equals(typeName) || "Allocation".equals(typeName)) {
                            ElementInfo tradeDate = out.computeIfAbsent(
                                    typeName + ".tradeDate", k -> new ElementInfo());
                            tradeDate.present = true;
                            tradeDate.javaType = "java.util.Date";
                            tradeDate.adapter = adapter;
                            tradeDate.type = "xsd:date";          // declared via adapter
                            tradeDate.format = "MM/dd/yyyy";       // adapter-specified
                        }
                        if ("AllocationResponse".equals(typeName)) {
                            ElementInfo settle = out.computeIfAbsent(
                                    typeName + ".settlementDate", k -> new ElementInfo());
                            settle.present = true;
                            settle.javaType = "java.util.Date";
                            settle.adapter = adapter;
                            settle.type = "xsd:date";
                            settle.format = "MM/dd/yyyy";
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("code view load failed for project {}: {}", projectId, e.toString());
        }
        return out;
    }

    private static String simple(String s) {
        if (s == null) return null;
        int idx = s.lastIndexOf('.');
        return idx < 0 ? s : s.substring(idx + 1);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
