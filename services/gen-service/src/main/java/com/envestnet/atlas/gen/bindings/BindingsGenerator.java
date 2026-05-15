package com.envestnet.atlas.gen.bindings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Pre-populates a JAXB bindings (.xjb) document by reading the project's
 * adapters from arch-service and projecting them into JAXB customizations:
 *
 *   - default package mapped to com.envestnet.&lt;vendor&gt;
 *   - any date adapter recovered in archaeology becomes an XmlAdapter binding
 *     for the wire fields with that adapter
 *
 * Output is opinionated for the demo project. The Translation Agent (Phase 1e)
 * will replace this with LLM-driven binding authorship.
 */
@Component
public class BindingsGenerator {
    private static final Logger log = LoggerFactory.getLogger(BindingsGenerator.class);

    private final RestTemplate http;
    private final String archUrl;

    public BindingsGenerator(RestTemplate http,
                             @Value("${ARCH_SERVICE_URL:http://arch-service:8083}") String archUrl) {
        this.http = http;
        this.archUrl = archUrl;
    }

    public String generate(UUID projectId, String basePackage) {
        List<Map<?, ?>> adapters = loadAdapters(projectId);

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<!--\n");
        sb.append("  Atlas Migrate · auto-generated JAXB bindings\n");
        sb.append("  Project: ").append(projectId).append('\n');
        sb.append("  Pre-populated from adapters detected in Stage A archaeology.\n");
        sb.append("  Override or extend before running generation.\n");
        sb.append("-->\n");
        sb.append("<jaxb:bindings version=\"3.0\"\n");
        sb.append("    xmlns:jaxb=\"https://jakarta.ee/xml/ns/jaxb\"\n");
        sb.append("    xmlns:xjc=\"https://jakarta.ee/xml/ns/jaxb/xjc\"\n");
        sb.append("    xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">\n\n");

        sb.append("  <jaxb:globalBindings>\n");
        sb.append("    <jaxb:javaType name=\"java.lang.String\" xmlType=\"xs:string\"/>\n");
        sb.append("    <jaxb:serializable uid=\"1\"/>\n");
        sb.append("  </jaxb:globalBindings>\n\n");

        sb.append("  <jaxb:bindings schemaLocation=\"authoritative.wsdl#types?schema1\">\n");
        sb.append("    <jaxb:schemaBindings>\n");
        sb.append("      <jaxb:package name=\"").append(basePackage).append("\"/>\n");
        sb.append("    </jaxb:schemaBindings>\n");

        for (Map<?, ?> a : adapters) {
            String fqn = String.valueOf(a.get("fqn"));
            String kind = String.valueOf(a.get("kind"));
            if (!"date".equals(kind)) continue;
            sb.append("\n");
            sb.append("    <!-- date adapter detected in archaeology: ").append(fqn).append(" -->\n");
            sb.append("    <jaxb:bindings node=\"//xs:element[@name='tradeDate']\" multiple=\"true\">\n");
            sb.append("      <jaxb:property>\n");
            sb.append("        <jaxb:baseType>\n");
            sb.append("          <jaxb:javaType name=\"java.lang.String\"\n");
            sb.append("            parseMethod=\"").append(basePackage).append(".adapters.DateAdapter.parse\"\n");
            sb.append("            printMethod=\"").append(basePackage).append(".adapters.DateAdapter.print\"/>\n");
            sb.append("        </jaxb:baseType>\n");
            sb.append("      </jaxb:property>\n");
            sb.append("    </jaxb:bindings>\n");
            sb.append("    <jaxb:bindings node=\"//xs:element[@name='settlementDate']\" multiple=\"true\">\n");
            sb.append("      <jaxb:property>\n");
            sb.append("        <jaxb:baseType>\n");
            sb.append("          <jaxb:javaType name=\"java.lang.String\"\n");
            sb.append("            parseMethod=\"").append(basePackage).append(".adapters.DateAdapter.parse\"\n");
            sb.append("            printMethod=\"").append(basePackage).append(".adapters.DateAdapter.print\"/>\n");
            sb.append("        </jaxb:baseType>\n");
            sb.append("      </jaxb:property>\n");
            sb.append("    </jaxb:bindings>\n");
        }

        sb.append("  </jaxb:bindings>\n");
        sb.append("</jaxb:bindings>\n");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private List<Map<?, ?>> loadAdapters(UUID projectId) {
        try {
            Object[] resp = http.getForObject(
                    archUrl + "/internal/archaeology/adapters/" + projectId, Object[].class);
            List<Map<?, ?>> out = new ArrayList<>();
            if (resp != null) {
                for (Object o : resp) if (o instanceof Map<?, ?> m) out.add(m);
            }
            return out;
        } catch (Exception e) {
            log.warn("adapter load failed for {}: {}", projectId, e.toString());
            return List.of();
        }
    }
}
