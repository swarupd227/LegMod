package com.envestnet.atlas.prj.forecast;

import com.envestnet.atlas.prj.domain.Project;
import com.envestnet.atlas.prj.repo.ProjectRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * The Migration Forecast pipeline:
 *
 *   1. Read the project's {@code sourcePath}.
 *   2. Ask arch-service to run a structural peek of the source tree
 *      ({@code SourceWalker} → counts + op summaries, no LLM, sub-second).
 *   3. Hand the structural facts to the LLM gateway with a prompt that
 *      asks for {@code estimatedWeeks}, a confidence tier, and the top
 *      three risk spikes — pinned to a JSON schema the model is told
 *      to honor.
 *   4. Persist the result in {@code prj.forecast} (upsert by project_id).
 *
 * The whole pipeline is synchronous and runs in well under 10 seconds on
 * the WS-I sample. Cost attribution flows through the LLM gateway as for
 * every other agent call in Atlas.
 */
@Service
public class ForecastService {

    private static final Logger log = LoggerFactory.getLogger(ForecastService.class);

    /**
     * SOAP-track system prompt. Used for projects with mode='SOAP'.
     * Calibrated against Apache Axis / Spring-WS / WebSphere SOAP
     * migrations to JAX-WS RI on Spring Boot.
     */
    private static final String SYSTEM_PROMPT_SOAP = """
            You are the Migration Forecast agent for Atlas Migrate, an
            AI-augmented SOAP/Framework migration platform.

            Your job: given a structural summary of a legacy codebase
            (file count, SOAP operations, type mappings, custom adapters,
            complexity flags), produce a calibrated effort forecast for
            modernizing it.

            You MUST respond with a single JSON object and nothing else.
            The JSON must conform exactly to this schema:

              {
                "estimatedWeeks": <integer, 1..52>,
                "confidence": "LOW" | "MEDIUM" | "HIGH",
                "topRisks": [
                  { "title": "<short noun phrase>",
                    "detail": "<one sentence>",
                    "severity": "LOW" | "MEDIUM" | "HIGH" }
                ],
                "rationale": "<two to four sentences in plain English, addressing the engineering tech lead. No bullet points. No headers.>"
              }

            Calibration anchors (use these to keep your estimates honest):
              - 5 ops, 2 adapters, simple type graph → 2-3 weeks, HIGH confidence
              - 20 ops, 5 adapters, RPC-encoded → 6-8 weeks, MEDIUM confidence
              - 50+ ops, custom faults, multi-fault, RPC-encoded → 12-16 weeks, MEDIUM confidence
              - >100 ops or unparseable flags → 16+ weeks, LOW confidence

            Be specific about risks. Mention the operations or adapters
            by name when they drive a risk. Avoid generic concerns like
            "schema drift" — Atlas's reconciliation stage handles those.
            Focus on what the engineering team will actually wrestle with:
            unusual adapters, RPC-encoded ops, custom fault types,
            namespace inconsistencies, suspicious operation count.

            Return exactly three risks, ordered most-severe first.
            """;

    /**
     * UPLIFT-track system prompt. Used for projects with mode='UPLIFT'.
     * Calibrated against Spring 3.x/4.x → Spring 6 / Boot 3 uplifts
     * with javax → jakarta, deprecated APIs, and library version
     * jumps as the dominant cost drivers.
     */
    private static final String SYSTEM_PROMPT_UPLIFT = """
            You are the Migration Forecast agent for Atlas Migrate, an
            AI-augmented framework-uplift platform.

            Your job: given a structural summary of a legacy Java
            codebase being uplifted (file count, complexity flags,
            and the source/target frameworks declared on the project),
            produce a calibrated effort forecast for the uplift.

            You MUST respond with a single JSON object and nothing else.
            The JSON must conform exactly to this schema:

              {
                "estimatedWeeks": <integer, 1..52>,
                "confidence": "LOW" | "MEDIUM" | "HIGH",
                "topRisks": [
                  { "title": "<short noun phrase>",
                    "detail": "<one sentence>",
                    "severity": "LOW" | "MEDIUM" | "HIGH" }
                ],
                "rationale": "<two to four sentences in plain English, addressing the engineering tech lead. No bullet points. No headers.>"
              }

            Calibration anchors for Spring 3.x/4.x → Spring 6 / Boot 3:
              - 30 files, javax → jakarta only            → 2-3 weeks, HIGH confidence
              - 60 files, deprecated APIs + Hibernate jump → 5-7 weeks, MEDIUM confidence
              - 150 files, custom XML config + WAR        → 10-14 weeks, MEDIUM confidence
              - >300 files or mixed Java/Kotlin           → 16+ weeks, LOW confidence

            Be specific about risks for an uplift: javax-to-jakarta
            renames, removed Spring APIs, security-namespace changes,
            persistence-provider upgrades, deprecated annotations,
            XML-based context configs, servlet container coupling,
            and any framework versions that skip multiple majors.
            Avoid generic concerns; cite the source-target framework
            transition Atlas was given.

            Return exactly three risks, ordered most-severe first.
            """;

    private final ProjectRepository projects;
    private final RestTemplate http;
    private final LlmClient llm;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final String archUrl;

    public ForecastService(ProjectRepository projects,
                           RestTemplate http,
                           LlmClient llm,
                           ObjectMapper mapper,
                           JdbcTemplate jdbc,
                           @Value("${ARCH_SERVICE_URL:http://arch-service:8084}") String archUrl) {
        this.projects = projects;
        this.http = http;
        this.llm = llm;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.archUrl = archUrl;
    }

    public Map<String, Object> run(UUID projectId) {
        Project p = projects.findById(projectId).orElseThrow(() ->
                new IllegalArgumentException("Project not found: " + projectId));
        if (p.sourcePath() == null || p.sourcePath().isBlank()) {
            throw new IllegalStateException(
                    "Project has no sourcePath — ingest a GitHub repo or upload a zip first.");
        }

        // 1. Structural peek via arch-service.
        Map<String, Object> peek = peekStructure(projectId, p.sourcePath());

        // 2. Build the LLM prompt grounded in the structural facts.
        // Mode-aware: SOAP migrations and UPLIFT uplifts have entirely
        // different cost drivers, so they get distinct system prompts.
        String system = "UPLIFT".equalsIgnoreCase(p.mode())
                ? SYSTEM_PROMPT_UPLIFT
                : SYSTEM_PROMPT_SOAP;
        String prompt = buildPrompt(p, peek);
        long t0 = System.currentTimeMillis();
        LlmClient.Response resp = llm.invoke(
                "forecast",
                "estimate",
                system,
                prompt,
                "sonnet",
                900);
        long elapsed = System.currentTimeMillis() - t0;

        // 3. Parse the model's JSON. On any parse failure, fall back to a
        //    deterministic estimate derived from the structural facts so
        //    the UI always has something to render.
        ForecastResult parsed = parseOrFallback(resp.text(), peek);

        // 4. Persist (upsert).
        persist(projectId, parsed, peek, resp, elapsed);

        // 5. Return the rendered forecast directly so the SPA doesn't
        //    have to follow up with a GET.
        return materialize(projectId, parsed, peek, resp, elapsed);
    }

    public Optional<Map<String, Object>> find(UUID projectId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT project_id, generated_at, estimated_weeks, confidence,
                       top_risks::text AS top_risks, rationale,
                       structural_facts::text AS structural_facts,
                       model, cost_usd, latency_ms
                  FROM prj.forecast
                 WHERE project_id = ?
                """, projectId);
        if (rows.isEmpty()) return Optional.empty();
        Map<String, Object> r = rows.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId",       r.get("project_id"));
        out.put("generatedAt",     r.get("generated_at"));
        out.put("estimatedWeeks",  r.get("estimated_weeks"));
        out.put("confidence",      r.get("confidence"));
        out.put("topRisks",        readJsonArray((String) r.get("top_risks")));
        out.put("rationale",       r.get("rationale"));
        out.put("structuralFacts", readJsonObject((String) r.get("structural_facts")));
        out.put("model",           r.get("model"));
        out.put("costUsd",         r.get("cost_usd"));
        out.put("latencyMs",       r.get("latency_ms"));
        return Optional.of(out);
    }

    // ----- internals ---------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> peekStructure(UUID projectId, String sourcePath) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of(
                "projectId", projectId.toString(),
                "sourcePath", sourcePath
        );
        Map<String, Object> resp = http.postForObject(
                archUrl + "/internal/archaeology/peek",
                new HttpEntity<>(body, h),
                Map.class);
        return resp == null ? Map.of() : resp;
    }

    private String buildPrompt(Project p, Map<String, Object> peek) {
        boolean uplift = "UPLIFT".equalsIgnoreCase(p.mode());
        StringBuilder sb = new StringBuilder();
        sb.append("Project: ").append(p.name()).append('\n');
        sb.append("Track: ").append(uplift ? "UPLIFT (framework uplift)" : "SOAP migration").append('\n');
        sb.append("Source framework: ").append(orDash(p.sourceFramework())).append('\n');
        sb.append("Target framework: ").append(orDash(p.targetFramework())).append('\n');
        sb.append("Vendor partner: ").append(orDash(p.vendorPartner())).append('\n');
        sb.append("Risk tier (declared): ").append(orDash(p.riskTier())).append('\n');
        sb.append('\n');
        sb.append("Structural peek of cloned source tree:\n");
        sb.append("  java files:       ").append(peek.getOrDefault("javaFileCount", 0)).append('\n');
        if (!uplift) {
            // SOAP-specific structural facts. For UPLIFT projects the
            // SourceWalker produces zero operations/types/adapters
            // because it's a SOAP-aware probe; surfacing those zeros
            // in the prompt would mislead the agent.
            sb.append("  SOAP operations:  ").append(peek.getOrDefault("operationCount", 0)).append('\n');
            sb.append("  type mappings:    ").append(peek.getOrDefault("typeMappingCount", 0)).append('\n');
            sb.append("  custom adapters:  ").append(peek.getOrDefault("adapterCount", 0)).append('\n');
        }

        Object flagsObj = peek.get("complexityFlags");
        if (flagsObj instanceof List<?> flags && !flags.isEmpty()) {
            sb.append("  complexity flags: ").append(String.join(", ",
                    flags.stream().map(Object::toString).toList())).append('\n');
        }

        Object opsObj = peek.get("operations");
        if (opsObj instanceof List<?> opsList && !opsList.isEmpty()) {
            sb.append('\n');
            sb.append("Operations (first ").append(Math.min(opsList.size(), 20)).append("):\n");
            int i = 0;
            for (Object o : opsList) {
                if (i++ >= 20) break;
                if (o instanceof Map<?, ?> opMap) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typed = (Map<String, Object>) opMap;
                    sb.append("  - ")
                            .append(typed.getOrDefault("name", "?"))
                            .append(" (").append(typed.getOrDefault("inputType", ""))
                            .append(" → ").append(typed.getOrDefault("outputType", ""))
                            .append(")\n");
                }
            }
        }
        sb.append('\n');
        sb.append("Produce the forecast JSON now.");
        return sb.toString();
    }

    private ForecastResult parseOrFallback(String text, Map<String, Object> peek) {
        // Try to extract a JSON object from the model's response.
        // The system prompt asks for JSON-only, but defensive parsing
        // protects against the occasional preamble or code fence.
        String json = extractJsonObject(text);
        if (json != null) {
            try {
                JsonNode root = mapper.readTree(json);
                int weeks = root.path("estimatedWeeks").asInt(0);
                String conf = root.path("confidence").asText("MEDIUM").toUpperCase(Locale.ROOT);
                if (!Set.of("LOW","MEDIUM","HIGH").contains(conf)) conf = "MEDIUM";
                String rationale = root.path("rationale").asText("");
                JsonNode risks = root.path("topRisks");
                if (weeks > 0 && !rationale.isBlank() && risks.isArray()) {
                    return new ForecastResult(weeks, conf, risks.toString(), rationale);
                }
            } catch (Exception e) {
                log.warn("Failed to parse forecast JSON: {}", e.toString());
            }
        }

        // Deterministic fallback. Calibrated to the same anchors as the
        // system prompt so the UI doesn't shock the engineer when the
        // model is unavailable.
        int ops = ((Number) peek.getOrDefault("operationCount", 0)).intValue();
        int adapters = ((Number) peek.getOrDefault("adapterCount", 0)).intValue();
        int weeks;
        String confidence;
        if (ops <= 7 && adapters <= 3) {
            weeks = 3;
            confidence = "HIGH";
        } else if (ops <= 25) {
            weeks = 6;
            confidence = "MEDIUM";
        } else if (ops <= 60) {
            weeks = 12;
            confidence = "MEDIUM";
        } else {
            weeks = 18;
            confidence = "LOW";
        }
        String rationale = "Forecast computed from structural facts only "
                + "(LLM response was unparseable). "
                + ops + " SOAP operations across " + adapters + " custom adapters.";
        String risks = """
                [
                  {"title":"Operation surface","detail":"Estimate scales linearly with operation count.","severity":"MEDIUM"},
                  {"title":"Adapter complexity","detail":"Custom adapters drive most of the type-mapping churn.","severity":"MEDIUM"},
                  {"title":"Empirical coverage","detail":"Confidence tightens once Stage B has captured production traffic.","severity":"LOW"}
                ]
                """.trim();
        return new ForecastResult(weeks, confidence, risks, rationale);
    }

    private void persist(UUID projectId, ForecastResult r, Map<String, Object> peek,
                         LlmClient.Response resp, long latencyMs) {
        String factsJson;
        try {
            factsJson = mapper.writeValueAsString(peek);
        } catch (Exception e) {
            factsJson = "{}";
        }
        BigDecimal cost = resp.costUsd == null ? null : BigDecimal.valueOf(resp.costUsd);
        // Upsert. If the engineer re-runs the forecast we keep one row
        // per project so the SPA's "Last estimated" timestamp is stable.
        jdbc.update("""
                INSERT INTO prj.forecast
                    (project_id, generated_at, estimated_weeks, confidence,
                     top_risks, rationale, structural_facts, model, cost_usd, latency_ms)
                VALUES (?, now(), ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?, ?)
                ON CONFLICT (project_id) DO UPDATE SET
                    generated_at     = now(),
                    estimated_weeks  = EXCLUDED.estimated_weeks,
                    confidence       = EXCLUDED.confidence,
                    top_risks        = EXCLUDED.top_risks,
                    rationale        = EXCLUDED.rationale,
                    structural_facts = EXCLUDED.structural_facts,
                    model            = EXCLUDED.model,
                    cost_usd         = EXCLUDED.cost_usd,
                    latency_ms       = EXCLUDED.latency_ms
                """,
                projectId,
                r.estimatedWeeks(),
                r.confidence(),
                r.topRisksJson(),
                r.rationale(),
                factsJson,
                resp.model,
                cost,
                latencyMs);
    }

    private Map<String, Object> materialize(UUID projectId, ForecastResult r,
                                            Map<String, Object> peek,
                                            LlmClient.Response resp, long latencyMs) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId",       projectId);
        out.put("generatedAt",     OffsetDateTime.now());
        out.put("estimatedWeeks",  r.estimatedWeeks());
        out.put("confidence",      r.confidence());
        out.put("topRisks",        readJsonArray(r.topRisksJson()));
        out.put("rationale",       r.rationale());
        out.put("structuralFacts", peek);
        out.put("model",           resp.model);
        out.put("costUsd",         resp.costUsd);
        out.put("latencyMs",       latencyMs);
        return out;
    }

    private Object readJsonArray(String s) {
        if (s == null || s.isBlank()) return List.of();
        try { return mapper.readValue(s, List.class); }
        catch (Exception e) { return List.of(); }
    }

    private Object readJsonObject(String s) {
        if (s == null || s.isBlank()) return Map.of();
        try { return mapper.readValue(s, Map.class); }
        catch (Exception e) { return Map.of(); }
    }

    private static String extractJsonObject(String s) {
        if (s == null) return null;
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        return s.substring(start, end + 1);
    }

    private static String orDash(String s) {
        return (s == null || s.isBlank()) ? "—" : s;
    }

    private record ForecastResult(int estimatedWeeks, String confidence,
                                  String topRisksJson, String rationale) {}
}
