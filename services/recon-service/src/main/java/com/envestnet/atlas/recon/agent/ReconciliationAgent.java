package com.envestnet.atlas.recon.agent;

import com.envestnet.atlas.recon.schema.ElementInfo;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * For each divergence: build a focused prompt that includes the three views,
 * ask the agent for a structured JSON recommendation, parse it. On any failure
 * we fall back to a deterministic recommendation derived from the divergence
 * kind so the queue is always populated.
 */
@Component
public class ReconciliationAgent {
    private static final Logger log = LoggerFactory.getLogger(ReconciliationAgent.class);

    private static final String SYSTEM = """
            You are the Schema Reconciliation Agent inside Atlas Migrate, a tool
            for migrating legacy SOAP services to JAX-WS. For one element where
            the vendor WSDL, the legacy code-derived schema, and the empirical
            schema (inferred from captured wire envelopes) disagree, choose ONE
            action:

              preserve_legacy   — keep the wire shape the legacy code emits
                                   (when partner systems would break otherwise)
              adopt_vendor      — switch to the vendor-declared shape
                                   (when the vendor WSDL is authoritative)
              promote_to_enum   — declare the field as a closed enum
                                   (when empirical values are clearly enum-like)
              add_to_schema     — declare a missing element
                                   (vendor doesn't see it, but it's on the wire)
              escalate          — needs human/SME judgment

            Reply with STRICT JSON ONLY, no prose, on a single line:
              {"action":"...", "rationale":"one short sentence", "confidence":"low|medium|high", "alternatives":["...","..."]}
            """;

    private final RestTemplate http;
    private final String llmUrl;
    private final ObjectMapper json = new ObjectMapper();

    public ReconciliationAgent(RestTemplate http,
                               @Value("${LLM_GATEWAY_URL:http://llm-gateway:8082}") String llmUrl) {
        this.http = http;
        this.llmUrl = llmUrl;
    }

    public Recommendation recommend(String path, String kind,
                                    ElementInfo vendor, ElementInfo code, ElementInfo empiric) {
        String prompt = buildPrompt(path, kind, vendor, code, empiric);

        Map<String, Object> body = Map.of(
                "agent", "reconciliation",
                "task",  "classify-divergence",
                "system", SYSTEM,
                "prompt", prompt,
                "modelHint", "sonnet",
                "maxTokens", 200
        );
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);

        try {
            LlmResponse resp = http.postForObject(llmUrl + "/api/v1/llm/invoke",
                    new HttpEntity<>(body, h), LlmResponse.class);
            if (resp == null) return fallback(kind, true, "no response", null);

            String text = resp.text();
            if (resp.stub) {
                return fallback(kind, true, "llm stub: " + text, resp.model);
            }
            return parse(text, resp.model);
        } catch (Exception e) {
            log.warn("recon agent call failed: {}", e.toString());
            return fallback(kind, true, "transport error: " + e.getMessage(), null);
        }
    }

    private String buildPrompt(String path, String kind,
                               ElementInfo v, ElementInfo c, ElementInfo e) {
        return """
                Element: %s
                Divergence kind: %s

                Vendor view:
                %s

                Code-derived view:
                %s

                Empirical view (from captured wire envelopes):
                %s

                Reply with the JSON only.
                """.formatted(path, kind,
                        toLines(v), toLines(c), toLines(e));
    }

    private String toLines(ElementInfo i) {
        if (!i.present) return "  (not present)";
        StringBuilder sb = new StringBuilder();
        Map<String, Object> map = i.toMap();
        map.forEach((k, val) -> sb.append("  ").append(k).append(": ").append(val).append("\n"));
        return sb.toString();
    }

    private Recommendation parse(String text, String model) {
        try {
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start < 0 || end <= start) return fallback("?", true, "no json found", model);
            String j = text.substring(start, end + 1);
            ParsedJson p = json.readValue(j, ParsedJson.class);
            return new Recommendation(
                    nz(p.action, "escalate"),
                    nz(p.rationale, "(no rationale)"),
                    nz(p.confidence, "medium"),
                    p.alternatives == null ? List.of() : p.alternatives,
                    model, false);
        } catch (Exception e) {
            return fallback("?", true, "parse error: " + e.getMessage(), model);
        }
    }

    private static Recommendation fallback(String kind, boolean stub, String reason, String model) {
        String action = switch (kind == null ? "" : kind) {
            case "format_difference" -> "preserve_legacy";
            case "type_lenience"     -> "preserve_legacy";
            case "enum_promotion"    -> "promote_to_enum";
            case "missing_vendor"    -> "add_to_schema";
            default                  -> "escalate";
        };
        return new Recommendation(
                action,
                stub ? "[stub] " + reason : reason,
                "medium",
                List.of("escalate"),
                model,
                stub);
    }

    private static String nz(String s, String dflt) {
        return s == null || s.isBlank() ? dflt : s;
    }

    public record Recommendation(
            String action,
            String rationale,
            String confidence,
            List<String> alternatives,
            String model,
            boolean stub
    ) {}

    public static class LlmResponse {
        public String model;
        public boolean stub;
        @JsonProperty("output") public Map<String, Object> output;

        public String text() {
            if (output == null) return "";
            Object t = output.get("text");
            return t == null ? "" : t.toString();
        }
    }

    private static class ParsedJson {
        public String action;
        public String rationale;
        public String confidence;
        public List<String> alternatives;
    }
}
