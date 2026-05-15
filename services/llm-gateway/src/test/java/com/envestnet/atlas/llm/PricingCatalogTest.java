package com.envestnet.atlas.llm;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-unit coverage of {@link PricingCatalog}.
 *
 * <p>The catalog is the source of truth for what callers get charged,
 * so the surface to pin is:
 *  • Known-model lookups produce the exact expected USD per the rate
 *    table.
 *  • Family-alias resolution (modelHint=sonnet vs. claude-sonnet-4-6)
 *    yields the same rate so a model bump doesn't require a price
 *    change.
 *  • Unknown models degrade to $0.00 rather than throwing.
 *  • Math is exact at the BigDecimal level so summing a million
 *    sub-cent calls in production gives the right total.</p>
 */
class PricingCatalogTest {

    /** Default rates baked into the constructor — Anthropic public 2026-05. */
    private final PricingCatalog catalog = defaultCatalog();

    /* ---------------- exact pricing ---------------- */

    @Test
    void sonnetAlias1MInputTokensCosts3Dollars() {
        // 1,000,000 input tokens * $3.00/M = $3.000000.
        BigDecimal cost = catalog.computeCost("sonnet", 1_000_000, 0);
        assertThat(cost).isEqualByComparingTo("3.000000");
    }

    @Test
    void sonnetAlias1MOutputTokensCosts15Dollars() {
        BigDecimal cost = catalog.computeCost("sonnet", 0, 1_000_000);
        assertThat(cost).isEqualByComparingTo("15.000000");
    }

    @Test
    void opusAliasCharges5xSonnetOnInputAnd5xOnOutput() {
        BigDecimal sonnet = catalog.computeCost("sonnet", 100_000, 50_000);
        BigDecimal opus   = catalog.computeCost("opus",   100_000, 50_000);
        // Opus list price is exactly 5x Sonnet on each leg.
        assertThat(opus).isEqualByComparingTo(sonnet.multiply(BigDecimal.valueOf(5)));
    }

    /* ---------------- family resolution ---------------- */

    @Test
    void concreteModelIdResolvesToFamilyRateViaPrefixMatch() {
        // A newly-released model id falls back to its family rate
        // until operators set an explicit override — keeps the
        // gateway from accidentally pricing new versions at $0.
        BigDecimal viaAlias    = catalog.computeCost("sonnet",                 1000, 1000);
        BigDecimal viaConcrete = catalog.computeCost("claude-sonnet-4-7-rc1",  1000, 1000);
        assertThat(viaConcrete).isEqualByComparingTo(viaAlias);
    }

    @Test
    void modelNameIsCaseInsensitive() {
        BigDecimal lower = catalog.computeCost("sonnet", 100, 100);
        BigDecimal upper = catalog.computeCost("SONNET", 100, 100);
        BigDecimal mixed = catalog.computeCost("Claude-Sonnet-4-6", 100, 100);
        assertThat(lower).isEqualByComparingTo(upper);
        assertThat(lower).isEqualByComparingTo(mixed);
    }

    /* ---------------- defensive defaults ---------------- */

    @Test
    void unknownModelDegradesToZeroRatherThanThrowing() {
        // Custom unknown-rate is zero in the production default — the
        // gateway must never break a user call over a pricing miss.
        BigDecimal cost = catalog.computeCost("gpt-9-supersonic", 1000, 1000);
        assertThat(cost).isEqualByComparingTo("0.000000");
    }

    @Test
    void zeroTokensIsZeroCostEvenForKnownModel() {
        BigDecimal cost = catalog.computeCost("sonnet", 0, 0);
        assertThat(cost.signum()).isZero();
    }

    @Test
    void nullTokensTreatedAsZero() {
        BigDecimal cost = catalog.computeCost("sonnet", null, null);
        assertThat(cost.signum()).isZero();
        // Also: a one-sided null should bill only the other half.
        BigDecimal inOnly = catalog.computeCost("sonnet", 1_000_000, null);
        assertThat(inOnly).isEqualByComparingTo("3.000000");
    }

    @Test
    void knowsReturnsFalseForCompletelyUnknownModel() {
        assertThat(catalog.knows("sonnet")).isTrue();
        assertThat(catalog.knows("claude-sonnet-4-7")).isTrue();
        assertThat(catalog.knows("gpt-9-supersonic")).isFalse();
        assertThat(catalog.knows(null)).isFalse();
        assertThat(catalog.knows("")).isFalse();
    }

    /* ---------------- exact math at scale ---------------- */

    @Test
    void milllionsOfSubCentCallsSumExactlyToTheirHeadlineRate() {
        // 1000 calls × 1000 input tokens × $3/M = $3.00. Each
        // call's cost is $0.003 (3 mills); summing the BigDecimals
        // must produce exactly $3.000000.
        BigDecimal acc = BigDecimal.ZERO;
        for (int i = 0; i < 1000; i++) {
            acc = acc.add(catalog.computeCost("sonnet", 1000, 0));
        }
        assertThat(acc).isEqualByComparingTo("3.000000");
    }

    @Test
    void allRatesReturnsTheFullTableForDiagnostics() {
        // The /pricing endpoint reads this — pinning its surface
        // here so a refactor doesn't accidentally hide a rate.
        assertThat(catalog.allRates()).containsKeys(
                "sonnet", "opus", "haiku",
                "claude-sonnet", "claude-opus", "claude-haiku");
    }

    /* ---------------- helpers ---------------- */

    private static PricingCatalog defaultCatalog() {
        return new PricingCatalog(
                new BigDecimal("3.00"),  new BigDecimal("15.00"),   // sonnet
                new BigDecimal("15.00"), new BigDecimal("75.00"),   // opus
                new BigDecimal("0.80"),  new BigDecimal("4.00"),    // haiku
                BigDecimal.ZERO,         BigDecimal.ZERO);          // unknown
    }
}
