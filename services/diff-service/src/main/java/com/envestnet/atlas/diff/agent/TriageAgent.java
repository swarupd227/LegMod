package com.envestnet.atlas.diff.agent;

import com.envestnet.atlas.diff.replay.Divergence;
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
 * Diff Triage Agent — receives a BATCH of divergences clustered by kind and
 * asks Claude to confirm bucket + propose a fix action. Batched calls keep
 * cost low: ~50 divergences per LLM invocation per the spec.
 *
 * If the gateway is stubbed or fails, we keep the heuristic bucket from the
 * Replayer so the lab is always populated.
 */
@Component
public class TriageAgent {
    private static final Logger log = LoggerFactory.getLogger(TriageAgent.class);

    private static final String SYSTEM = """
            You are the Diff Triage Agent inside Atlas Migrate. For each
            divergence between the legacy SOAP wire form and the regenerated
            JAX-WS wire form, decide the bucket and recommend an action:

              benign  — purely cosmetic (namespace prefix, attribute order,
                        whitespace, optional element ordering); no partner
                        impact
              amber   — wire-shape change a partner could plausibly notice
                        (date format, numeric precision, type lenience).
                        Usually fixable via a binding tweak.
              red     — broken behaviour: missing/extra elements, wrong values,
                        fault structure mismatch. Real bug.

            For amber divergences propose a binding action:
              fix_date_adapter | fix_decimal_precision | escalate

            Reply with strict JSON only, on a single line, with one entry per
            input id, no prose:
              [{"id":"...","bucket":"benign|amber|red","action":"...","rationale":"one short sentence"}]
            """;

    private final RestTemplate http;
    private final String llmUrl;
    private final ObjectMapper json = new ObjectMapper();

    public TriageAgent(RestTemplate http,
                       @Value("${LLM_GATEWAY_URL:http://llm-gateway:8082}") String llmUrl) {
        this.http = http;
        this.llmUrl = llmUrl;
    }

    public List<Verdict> classify(List<Divergence> batch) {
        if (batch.isEmpty()) return List.of();

        StringBuilder prompt = new StringBuilder("Classify these divergences:\n\n");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) {
            Divergence d = batch.get(i);
            String id = "d" + i;
            ids.add(id);
            prompt.append(String.format("- id=%s kind=%s xpath=%s legacy=%s new=%s%n  summary=%s%n",
                    id, d.kind, d.xpath,
                    safe(d.legacyValue), safe(d.newValue), safe(d.humanSummary)));
        }
        prompt.append("\nReturn the JSON array now.");

        // ~40 tokens per verdict × batch size = need 2-3k tokens to safely
        // emit a closed JSON array. 600 was truncating mid-array.
        Map<String, Object> body = Map.of(
                "agent",  "diff-triage",
                "task",   "classify-batch",
                "system", SYSTEM,
                "prompt", prompt.toString(),
                "modelHint", "sonnet",
                "maxTokens", 2500
        );
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);

        try {
            LlmResponse resp = http.postForObject(llmUrl + "/api/v1/llm/invoke",
                    new HttpEntity<>(body, h), LlmResponse.class);
            if (resp == null || resp.stub) return fallback(batch, true,
                    resp == null ? "no response" : "llm stub", resp == null ? null : resp.model);

            String text = resp.text();
            int start = text.indexOf('[');
            int end   = text.lastIndexOf(']');
            if (start < 0 || end <= start) return fallback(batch, true, "no json", resp.model);
            ParsedItem[] items = json.readValue(text.substring(start, end + 1), ParsedItem[].class);

            // Map back by id.
            Map<String, ParsedItem> byId = new LinkedHashMap<>();
            for (ParsedItem p : items) if (p.id != null) byId.put(p.id, p);

            List<Verdict> out = new ArrayList<>();
            for (int i = 0; i < batch.size(); i++) {
                ParsedItem p = byId.get(ids.get(i));
                Divergence d = batch.get(i);
                out.add(p == null
                        ? Verdict.fromHeuristic(d, resp.model, false, "agent skipped this id")
                        : Verdict.of(p.bucket == null ? d.bucket : p.bucket,
                                p.action == null ? defaultAction(d) : p.action,
                                p.rationale == null ? "(no rationale)" : p.rationale,
                                resp.model, false));
            }
            return out;
        } catch (Exception e) {
            log.warn("triage agent call failed: {}", e.toString());
            return fallback(batch, true, "transport: " + e.getMessage(), null);
        }
    }

    private List<Verdict> fallback(List<Divergence> batch, boolean stub, String reason, String model) {
        return batch.stream()
                .map(d -> Verdict.fromHeuristic(d, model, stub, reason))
                .toList();
    }

    private String defaultAction(Divergence d) {
        return switch (d.kind) {
            case "date_format"      -> "fix_date_adapter";
            case "type_precision"   -> "fix_decimal_precision";
            case "missing_element", "value_diff" -> "escalate";
            default                 -> "accept_benign";
        };
    }

    private static String safe(String s) { return s == null ? "" : s; }

    public record Verdict(
            String bucket,
            String action,
            String rationale,
            String model,
            boolean stub
    ) {
        public static Verdict of(String bucket, String action, String rationale,
                                 String model, boolean stub) {
            return new Verdict(bucket, action, rationale, model, stub);
        }
        public static Verdict fromHeuristic(Divergence d, String model, boolean stub, String reason) {
            String act = switch (d.kind) {
                case "date_format"     -> "fix_date_adapter";
                case "type_precision"  -> "fix_decimal_precision";
                case "missing_element" -> "escalate";
                default                -> "accept_benign";
            };
            return new Verdict(
                    d.bucket,
                    act,
                    stub ? "[stub] " + reason : "heuristic classification",
                    model, stub);
        }
    }

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

    private static class ParsedItem {
        public String id;
        public String bucket;
        public String action;
        public String rationale;
    }
}
