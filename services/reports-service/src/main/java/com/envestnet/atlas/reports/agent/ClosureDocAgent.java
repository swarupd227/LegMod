package com.envestnet.atlas.reports.agent;

import com.envestnet.atlas.reports.gather.ProjectFacts;
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
 * Generates the Migration Closure document — the human-readable narrative the
 * engagement team hands to the client alongside the source bundle. The agent
 * is given a structured snapshot of every stage's result and asked to produce
 * a markdown report under firm headings (the spec's exact section list).
 *
 * If the LLM is unavailable we still emit a deterministic summary so the
 * bundle is never empty.
 */
@Component
public class ClosureDocAgent {
    private static final Logger log = LoggerFactory.getLogger(ClosureDocAgent.class);

    private static final String SYSTEM = """
            You are the Closure Doc author for Atlas Migrate. The engagement
            team will hand the document below to the client at handoff. Write
            crisp, factual prose — no marketing tone, no emojis. You receive
            a JSON snapshot of every stage (project metadata, operations,
            adapters, captured envelopes, reconciliation decisions, generated
            files, differential diff buckets). Use ONLY facts present in the
            snapshot — do not invent counts, decisions, or risks.

            Output FORMAT: GitHub-flavoured markdown, exactly these H2 sections
            in this order, each 1-3 short paragraphs:

              ## Scope and target
              ## Approach
              ## Notable decisions
              ## Test coverage
              ## Residual risks
              ## Recommended pre-production gates
              ## Maintenance notes

            No preamble before the first H2. No conclusion after the last.
            Keep the whole document under ~700 words.
            """;

    private final RestTemplate http;
    private final String llmUrl;
    private final ObjectMapper json = new ObjectMapper();

    public ClosureDocAgent(RestTemplate http,
                           @Value("${LLM_GATEWAY_URL:http://llm-gateway:8082}") String llmUrl) {
        this.http = http;
        this.llmUrl = llmUrl;
    }

    public Result generate(ProjectFacts.Snapshot s) {
        String snapshotJson = compactSnapshot(s);
        String prompt = """
                Project snapshot (compact JSON):
                %s

                Write the closure document now.
                """.formatted(snapshotJson);

        Map<String, Object> body = Map.of(
                "agent", "closure-doc",
                "task",  "write-narrative",
                "system", SYSTEM,
                "prompt", prompt,
                "modelHint", "opus",
                "maxTokens", 1800
        );
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);

        try {
            LlmResponse resp = http.postForObject(llmUrl + "/api/v1/llm/invoke",
                    new HttpEntity<>(body, h), LlmResponse.class);
            if (resp == null) return fallback(s, true, "no response", null);
            if (resp.stub)    return new Result(deterministic(s) + stubSuffix(resp.text()), true, resp.model);
            return new Result(resp.text().trim(), false, resp.model);
        } catch (Exception e) {
            log.warn("closure agent call failed: {}", e.toString());
            return fallback(s, true, "transport: " + e.getMessage(), null);
        }
    }

    private Result fallback(ProjectFacts.Snapshot s, boolean stub, String reason, String model) {
        return new Result(deterministic(s) + "\n\n_[stub: " + reason + "]_\n", stub, model);
    }

    /** Compact JSON projection — only fields the agent should see. */
    private String compactSnapshot(ProjectFacts.Snapshot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("project", project(s));
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("operations",         s.operations.size());
        counts.put("adapters",           s.adapters.size());
        counts.put("envelopes_captured", asLong(get(s.captureStatus, "totalEnvelopes")));
        counts.put("decisions_total",    asLong(getNested(s.reconStatus, "counts", "total")));
        counts.put("decisions_pending",  asLong(getNested(s.reconStatus, "counts", "pending")));
        counts.put("decisions_resolved", asLong(getNested(s.reconStatus, "counts", "resolved")));
        counts.put("files_generated",    asLong(getNested(s.genStatus, "run", "fileCount")));
        counts.put("diff_pass",          asLong(getNested(s.diffStatus, "run", "passCount")));
        counts.put("diff_benign",        asLong(getNested(s.diffStatus, "run", "benignCount")));
        counts.put("diff_amber",         asLong(getNested(s.diffStatus, "run", "amberCount")));
        counts.put("diff_red",           asLong(getNested(s.diffStatus, "run", "redCount")));
        m.put("counts", counts);
        m.put("first_three_decisions", takeFirst(getNested(s.reconStatus, "decisions"), 3));
        m.put("first_three_red_diffs", takeFirstFiltered(getNested(s.diffStatus, "divergences"), "red", 3));
        try { return json.writeValueAsString(m); }
        catch (Exception e) { return "{}"; }
    }

    private Map<String, Object> project(ProjectFacts.Snapshot s) {
        Map<String, Object> p = new LinkedHashMap<>();
        for (String k : List.of("name", "mode", "sourceFramework", "targetFramework",
                "vendorPartner", "owner", "riskTier", "currentStage")) {
            p.put(k, s.project.get(k));
        }
        return p;
    }

    /** Used as the body of the bundle's closure.md when the LLM is stubbed. */
    public String deterministic(ProjectFacts.Snapshot s) {
        Map<String, Object> p = s.project;
        long ops = s.operations.size();
        long adapters = s.adapters.size();
        long envs = asLong(get(s.captureStatus, "totalEnvelopes"));
        long decResolved = asLong(getNested(s.reconStatus, "counts", "resolved"));
        long decTotal = asLong(getNested(s.reconStatus, "counts", "total"));
        long files = asLong(getNested(s.genStatus, "run", "fileCount"));
        long red   = asLong(getNested(s.diffStatus, "run", "redCount"));
        long amber = asLong(getNested(s.diffStatus, "run", "amberCount"));
        long benign = asLong(getNested(s.diffStatus, "run", "benignCount"));
        long pass  = asLong(getNested(s.diffStatus, "run", "passCount"));

        return ("""
                ## Scope and target

                %s migration of %s · vendor partner **%s**.
                Track: %s. Target framework: %s.

                ## Approach

                Stage A recovered %d operations and %d adapters from the legacy source.
                Stage B captured %,d wire envelopes against the live partner endpoint and
                sanitised them at the edge. Stage C reconciled the vendor WSDL, the
                code-derived schema, and the empirical schema; %d of %d divergences were
                resolved by the engineer. Stage D generated %,d Java JAX-WS source files
                via wsimport against the Authoritative WSDL. Stage E replayed the captured
                corpus through the regenerated wire shape and triaged differences.

                ## Notable decisions

                See `reports/decisions.json` in this bundle for the full audit trail.

                ## Test coverage

                Differential validation: %,d byte-equivalent · %,d benign · %,d amber · %,d red.

                ## Residual risks

                %s

                ## Recommended pre-production gates

                Run the bundled `differential.json` replay against the partner sandbox.
                Sweeper-component integration tests are scheduled for the v1.1 deliverable.

                ## Maintenance notes

                Re-run reconciliation if the vendor publishes an updated WSDL.
                Bindings live in `bindings.xjb`; regeneration is one command in Stage D.
                """).formatted(
                p.getOrDefault("mode", "SOAP"),
                p.getOrDefault("name", "(unnamed)"),
                p.getOrDefault("vendorPartner", "—"),
                p.getOrDefault("mode", "—"),
                p.getOrDefault("targetFramework", "—"),
                ops, adapters, envs,
                decResolved, decTotal, files,
                pass, benign, amber, red,
                red > 0
                    ? red + " red-bucket divergence(s) remain unresolved — see `differential.json`"
                    : "No red-bucket divergences remain."
        );
    }

    /* ---------------- helpers ---------------- */

    private static long asLong(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(o.toString()); } catch (Exception e) { return 0; }
    }
    private static Object get(Map<String, Object> m, String key) {
        return m == null ? null : m.get(key);
    }
    private static Object getNested(Map<String, Object> m, String... keys) {
        Object o = m;
        for (String k : keys) {
            if (o instanceof Map<?, ?> mm) o = mm.get(k);
            else return null;
        }
        return o;
    }
    private static Object getNested(Map<String, Object> m, String key) {
        return m == null ? null : m.get(key);
    }
    private static List<?> takeFirst(Object listLike, int n) {
        if (listLike instanceof List<?> l) {
            return l.size() <= n ? l : l.subList(0, n);
        }
        return List.of();
    }
    private static List<?> takeFirstFiltered(Object listLike, String bucket, int n) {
        if (!(listLike instanceof List<?> l)) return List.of();
        List<Object> out = new ArrayList<>();
        for (Object o : l) {
            if (o instanceof Map<?, ?> m && bucket.equals(m.get("bucket"))) {
                out.add(m);
                if (out.size() >= n) break;
            }
        }
        return out;
    }
    private static String stubSuffix(String t) {
        return "\n\n_[stub fallback used — narrative below is deterministic]_\n_response: " + t + "_\n";
    }

    public record Result(String markdown, boolean stub, String model) {}

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
}
