package com.envestnet.atlas.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.envestnet.atlas.llm.logging.RequestContextFilter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@SpringBootApplication
public class LlmApplication {
    public static void main(String[] args) {
        SpringApplication.run(LlmApplication.class, args);
    }

    @Bean
    public AnthropicClient anthropicClient(@Value("${ANTHROPIC_API_KEY:}") String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return null; // gateway will return stubbed responses
        }
        return AnthropicOkHttpClient.builder().apiKey(apiKey).build();
    }
}

@RestController
@RequestMapping("/api/v1/llm")
class LlmController {
    private static final Logger log = LoggerFactory.getLogger(LlmController.class);

    private final AnthropicClient client;
    private final PricingCatalog pricing;
    private final String opusModel;
    private final String sonnetModel;
    private final String defaultModel;
    private final MeterRegistry meterRegistry;

    LlmController(@Autowired(required = false) @Nullable AnthropicClient client,
                  PricingCatalog pricing,
                  MeterRegistry meterRegistry,
                  @Value("${LLM_MODEL_OPUS:claude-opus-4-7}") String opus,
                  @Value("${LLM_MODEL_SONNET:claude-sonnet-4-6}") String sonnet,
                  @Value("${LLM_DEFAULT_MODEL:sonnet}") String defaultModel) {
        this.client = client;
        this.pricing = pricing;
        this.meterRegistry = meterRegistry;
        this.opusModel = opus;
        this.sonnetModel = sonnet;
        this.defaultModel = defaultModel;
    }

    /**
     * {@code atlas_llm_invocation_seconds{model,outcome}} — wall-clock
     * histogram of LLM round-trips. {@code outcome} is one of
     * {@code success}, {@code stub_no_key}, {@code stub_error}.
     */
    private Timer.Sample startTimer() { return Timer.start(meterRegistry); }

    private void stopTimer(Timer.Sample sample, String model, String outcome) {
        sample.stop(Timer.builder("atlas_llm_invocation_seconds")
                .description("LLM round-trip latency, by model and outcome")
                .tag("model", model)
                .tag("outcome", outcome)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry));
    }

    @PostMapping("/invoke")
    public Map<String, Object> invoke(@RequestBody InvokeRequest req,
                                       HttpServletRequest httpReq) {
        String resolvedModel = resolveModel(req.modelHint());
        // Gather caller-asserted identity from the gateway-stamped headers.
        // The gateway already validated them, so we trust them as-is for
        // attribution purposes — see docs/auth.md for the trust model.
        InvokeContext ctx = readContext(httpReq);

        if (client == null) {
            // Stub path: still time it so dashboards see the request rate
            // even when the API key isn't configured (development mode).
            Timer.Sample sample = startTimer();
            try {
                return stub(resolvedModel, "no ANTHROPIC_API_KEY configured", ctx);
            } finally {
                stopTimer(sample, resolvedModel, "stub_no_key");
            }
        }

        Timer.Sample sample = startTimer();
        long start = System.currentTimeMillis();
        try {
            Message message = client.messages().create(MessageCreateParams.builder()
                    .model(Model.of(resolvedModel))
                    .maxTokens(req.maxTokens() == null ? 1024 : req.maxTokens())
                    .system(req.system() == null ? "" : req.system())
                    .addUserMessage(req.prompt() == null ? "" : req.prompt())
                    .build());
            long elapsed = System.currentTimeMillis() - start;

            String text = message.content().stream()
                    .filter(b -> b.text().isPresent())
                    .map(b -> b.text().get().text())
                    .reduce("", (a, b) -> a + b);

            int tIn  = Math.toIntExact(message.usage().inputTokens());
            int tOut = Math.toIntExact(message.usage().outputTokens());
            BigDecimal cost = pricing.computeCost(resolvedModel, tIn, tOut);

            // Per-workspace cost counter — Grafana dashboards subscribe
            // to this for the "spend by tenant" panel. Tag with the
            // resolved model so a cost spike can be traced to whether
            // a tenant flipped to Opus.
            recordCostCounter(cost, resolvedModel, ctx);

            stopTimer(sample, resolvedModel, "success");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", resolvedModel);
            body.put("stub", false);
            body.put("output", Map.of("text", text));
            body.put("tokens", Map.of("in", tIn, "out", tOut));
            // Echo cost as a primitive double — JSON consumers (other
            // services, the SPA) get a real number, not a string.
            // NUMERIC(10,4) in prov.entry will round at storage time.
            body.put("cost_usd", cost.setScale(4, RoundingMode.HALF_UP).doubleValue());
            body.put("latency_ms", elapsed);
            body.put("context", ctx.toMap());
            return body;
        } catch (Exception e) {
            // Never 500 on upstream LLM failures — degrade to a stub so the
            // archaeology / reconciliation pipeline keeps running. The reason
            // is surfaced in the stub text and in service logs.
            log.warn("Anthropic call failed ({}); returning stub", e.getMessage());
            stopTimer(sample, resolvedModel, "stub_error");
            return stub(resolvedModel, e.getMessage(), ctx);
        }
    }

    private static Map<String, Object> stub(String model, String reason, InvokeContext ctx) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("stub", true);
        body.put("output", Map.of("text", "[stub] " + reason));
        body.put("tokens", Map.of("in", 0, "out", 0));
        body.put("cost_usd", 0.0);
        body.put("latency_ms", 0);
        body.put("context", ctx.toMap());
        return body;
    }

    /**
     * Increment the per-workspace, per-model USD counter. Cost is
     * cumulative double precision in Micrometer's internal counter, so
     * even very-low-cost calls (sub-cent) add up correctly. We use
     * BigDecimal everywhere upstream for exact storage, but the
     * counter is a real-time visibility tool — double precision is
     * plenty for "spend in the last hour".
     */
    private void recordCostCounter(BigDecimal cost, String model, InvokeContext ctx) {
        if (cost.signum() <= 0) return;
        Tags tags = Tags.of(
                Tag.of("model",     model),
                Tag.of("workspace", ctx.workspaceId == null ? "unknown" : ctx.workspaceId),
                // Don't tag with email — high-cardinality tags blow up
                // the metrics registry. Per-user attribution is read
                // from the provenance DB; the metric is the rollup view.
                Tag.of("known",     String.valueOf(pricing.knows(model)))
        );
        Counter.builder("atlas_llm_cost_usd_total")
                .description("Cumulative LLM cost in USD, tagged by workspace and model")
                .tags(tags)
                .register(meterRegistry)
                .increment(cost.doubleValue());
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("ok", true, "configured", client != null,
                "models", Map.of("opus", opusModel, "sonnet", sonnetModel, "default", defaultModel));
    }

    /**
     * Diagnostics endpoint — dumps the current pricing catalog so
     * operators can sanity-check what callers are being charged. Read-
     * only; no auth required because the rates aren't sensitive.
     */
    @GetMapping("/pricing")
    public Map<String, Object> pricing() {
        Map<String, Object> out = new LinkedHashMap<>();
        pricing.allRates().forEach((model, rate) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("inputPerMillionUsd",  rate.inputPerMillion());
            m.put("outputPerMillionUsd", rate.outputPerMillion());
            out.put(model, m);
        });
        return Map.of("rates", out);
    }

    private String resolveModel(String hint) {
        if (hint == null || "auto".equalsIgnoreCase(hint)) {
            hint = defaultModel;
        }
        return switch (hint.toLowerCase(Locale.ROOT)) {
            case "opus" -> opusModel;
            case "sonnet" -> sonnetModel;
            default -> hint;
        };
    }

    public record InvokeRequest(
            String agent,
            String task,
            String system,
            String prompt,
            String modelHint,
            Integer maxTokens
    ) {}

    /**
     * Caller-asserted identity, read from the gateway-stamped request
     * headers. Echoed back in the response so callers can re-thread
     * the values into provenance without parsing the headers
     * themselves — important because not every caller has the
     * request context wired (e.g. background tasks).
     *
     * <p>Workspace + project + email are <i>attribution metadata</i>:
     * the LLM gateway doesn't enforce access (that's
     * {@code prj-service}'s job) but it does record who-spent-what so
     * the audit + cost-rollup layers can attribute correctly.</p>
     */
    private static final class InvokeContext {
        final String workspaceId;
        final String projectId;
        final String userEmail;
        final String requestId;

        InvokeContext(String workspaceId, String projectId, String userEmail, String requestId) {
            this.workspaceId = workspaceId;
            this.projectId   = projectId;
            this.userEmail   = userEmail;
            this.requestId   = requestId;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("workspaceId", workspaceId);
            m.put("projectId",   projectId);
            m.put("userEmail",   userEmail);
            m.put("requestId",   requestId);
            return m;
        }
    }

    private static InvokeContext readContext(HttpServletRequest req) {
        return new InvokeContext(
                trimToNull(req.getHeader(RequestContextFilter.HDR_WORKSPACE_ID)),
                trimToNull(req.getHeader(RequestContextFilter.HDR_PROJECT_ID)),
                trimToNull(req.getHeader(RequestContextFilter.HDR_USER_EMAIL)),
                trimToNull(req.getHeader(RequestContextFilter.HDR_REQUEST_ID))
        );
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
