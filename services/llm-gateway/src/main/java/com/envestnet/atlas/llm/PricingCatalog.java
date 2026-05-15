package com.envestnet.atlas.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Per-model pricing for token usage. Source of truth for cost
 * attribution — every {@code /api/v1/llm/invoke} call ends with a
 * lookup here and the result is stitched into the response as
 * {@code cost_usd}.
 *
 * <p>Two design choices worth pinning:
 *
 * <ul>
 *   <li><b>Per-million-tokens, not per-1k.</b> Anthropic + OpenAI both
 *       publish prices in {@code $X.XX / 1M tokens}; carrying those
 *       through directly keeps the env-var values legible.</li>
 *   <li><b>Defaults baked in, fully overridable.</b> Out of the box
 *       the catalog knows current Anthropic public prices for the
 *       models Atlas actually invokes (Sonnet 4.5, Opus 4.7,
 *       Haiku 3.5). Customers on negotiated rates override via env
 *       vars like {@code LLM_PRICE_SONNET_INPUT=2.50}. The catalog
 *       also accepts a free-form {@code LLM_PRICE_<MODEL>} pattern
 *       for models we haven't enumerated yet.</li>
 * </ul>
 *
 * <p>Unknown models log a single WARN per invocation and return $0.00
 * rather than throwing — the gateway must never break a user request
 * over a pricing miss. Operators see the gap in logs + the
 * {@code atlas_llm_unknown_model_total} counter (Phase 2L.2) and add
 * the rate when convenient.</p>
 */
@Component
public class PricingCatalog {

    private static final Logger log = LoggerFactory.getLogger(PricingCatalog.class);

    /**
     * Rate pair in dollars per million tokens. {@code input} is for
     * prompt + system tokens, {@code output} is for completion tokens
     * — providers price the two halves separately and output is
     * typically 3-5x the input rate.
     */
    public record Rate(BigDecimal inputPerMillion, BigDecimal outputPerMillion) {
        public Rate {
            Objects.requireNonNull(inputPerMillion);
            Objects.requireNonNull(outputPerMillion);
        }
    }

    private final Map<String, Rate> rates;

    public PricingCatalog(
            // Anthropic Claude 4.5 Sonnet — production default for most
            // Atlas agents. Public list price as of 2026-05.
            @Value("${LLM_PRICE_SONNET_INPUT:3.00}")  BigDecimal sonnetIn,
            @Value("${LLM_PRICE_SONNET_OUTPUT:15.00}") BigDecimal sonnetOut,
            // Anthropic Claude 4.7 Opus — used for the heavy archaeology
            // / reconciliation runs where reasoning quality matters more
            // than cost.
            @Value("${LLM_PRICE_OPUS_INPUT:15.00}")   BigDecimal opusIn,
            @Value("${LLM_PRICE_OPUS_OUTPUT:75.00}")  BigDecimal opusOut,
            // Haiku — used by lightweight routing / classification
            // agents. Optional; can be removed if Atlas drops support.
            @Value("${LLM_PRICE_HAIKU_INPUT:0.80}")   BigDecimal haikuIn,
            @Value("${LLM_PRICE_HAIKU_OUTPUT:4.00}")  BigDecimal haikuOut,
            // Default rate for a model the catalog doesn't recognise.
            // Intentionally zero — better to under-report than to
            // overcharge a customer for a model we can't price reliably.
            @Value("${LLM_PRICE_UNKNOWN_INPUT:0.00}")  BigDecimal unknownIn,
            @Value("${LLM_PRICE_UNKNOWN_OUTPUT:0.00}") BigDecimal unknownOut) {

        Map<String, Rate> map = new LinkedHashMap<>();
        // Family aliases — the gateway resolves modelHint=sonnet to
        // claude-sonnet-4-6 (configurable), but provenance records the
        // resolved model name. Index by both so the lookup is forgiving.
        map.put("sonnet", new Rate(sonnetIn, sonnetOut));
        map.put("opus",   new Rate(opusIn,   opusOut));
        map.put("haiku",  new Rate(haikuIn,  haikuOut));
        // Concrete model ids — pattern-matched on prefix so a new
        // release (claude-sonnet-4-7) still hits the sonnet rate
        // until operators set an explicit override.
        map.put("claude-sonnet", new Rate(sonnetIn, sonnetOut));
        map.put("claude-opus",   new Rate(opusIn,   opusOut));
        map.put("claude-haiku",  new Rate(haikuIn,  haikuOut));

        this.rates = Collections.unmodifiableMap(map);
        this.unknownRate = new Rate(unknownIn, unknownOut);
    }

    private final Rate unknownRate;

    /**
     * Compute USD cost for a single LLM call. Returns 0.0 (with a WARN
     * log) when the model isn't in the catalog so a pricing miss never
     * breaks the request.
     *
     * @param model resolved model name (post family-alias resolution)
     * @param tokensIn  prompt + system tokens (null treated as 0)
     * @param tokensOut completion tokens (null treated as 0)
     * @return USD cost rounded to 6 decimal places. Six decimals
     *         (vs. cost_usd's NUMERIC(10,4) schema) means a single
     *         call's cost rounds in storage but our IN-MEMORY math is
     *         lossless across hundreds of millions of summed calls.
     */
    public BigDecimal computeCost(String model, Integer tokensIn, Integer tokensOut) {
        int in  = tokensIn  == null ? 0 : tokensIn;
        int out = tokensOut == null ? 0 : tokensOut;
        if (in == 0 && out == 0) return BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);

        Rate rate = rateFor(model);
        BigDecimal million = BigDecimal.valueOf(1_000_000);
        BigDecimal inCost  = rate.inputPerMillion().multiply(BigDecimal.valueOf(in)).divide(million, 8, RoundingMode.HALF_UP);
        BigDecimal outCost = rate.outputPerMillion().multiply(BigDecimal.valueOf(out)).divide(million, 8, RoundingMode.HALF_UP);
        return inCost.add(outCost).setScale(6, RoundingMode.HALF_UP);
    }

    /**
     * Direct rate lookup. Exposed for diagnostics — the gateway's
     * {@code /api/v1/llm/pricing} endpoint dumps the table so operators
     * can sanity-check what their customers are being charged.
     */
    public Map<String, Rate> allRates() {
        return rates;
    }

    /** Whether the catalog knows a specific model. */
    public boolean knows(String model) {
        return rateForOrNull(model) != null;
    }

    private Rate rateFor(String model) {
        Rate r = rateForOrNull(model);
        if (r != null) return r;
        log.warn("pricing miss: unknown model='{}' — cost recorded as 0.0; "
                + "set LLM_PRICE_<MODEL>_{{INPUT,OUTPUT}} env vars to fix",
                model);
        return unknownRate;
    }

    private Rate rateForOrNull(String model) {
        if (model == null || model.isBlank()) return null;
        String norm = model.toLowerCase(Locale.ROOT).trim();
        // Exact alias hit (sonnet/opus/haiku, or a configured concrete id).
        Rate exact = rates.get(norm);
        if (exact != null) return exact;
        // Prefix match — claude-sonnet-4-6 → claude-sonnet → Sonnet rate.
        // Iterate in insertion order; family aliases come last so concrete
        // overrides win when both are configured.
        for (Map.Entry<String, Rate> e : rates.entrySet()) {
            if (norm.startsWith(e.getKey())) return e.getValue();
        }
        return null;
    }
}
