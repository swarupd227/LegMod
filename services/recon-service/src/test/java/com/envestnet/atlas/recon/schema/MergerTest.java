package com.envestnet.atlas.recon.schema;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MergerTest {

    private final Merger merger = new Merger();

    @Test
    void presentInAllThreeViewsWithMatchingShapesProducesNoDivergence() {
        var v = info("xsd:string", null, null, null);
        var c = info("xsd:string", null, "java.lang.String", null);
        var e = info("xsd:string", null, null, null);

        var out = merger.merge(
                Map.of("/svc:request/svc:name", v),
                Map.of("/svc:request/svc:name", c),
                Map.of("/svc:request/svc:name", e));

        assertThat(out.elements).hasSize(1);
        assertThat(out.divergences).isEmpty();
    }

    @Test
    void vendorOnlyPathIsSilentlyDropped() {
        // Vendor declares it, but neither code nor empirical mention it. The
        // merger considers this not worth surfacing — it just means the WSDL
        // is more granular than what the corpus exercised.
        var v = info("xsd:string", null, null, null);

        var out = merger.merge(
                Map.of("/svc:request/svc:obscure", v),
                Map.of(),
                Map.of());

        assertThat(out.elements).hasSize(1);
        assertThat(out.divergences).isEmpty();
    }

    @Test
    void missingVendorWithEmpiricalObservationsProducesMediumImpact() {
        var c = info("xsd:string", null, "java.lang.String", null);
        var e = info("xsd:string", null, null, null);
        e.observed = 42L;

        var out = merger.merge(
                Map.of(),
                Map.of("/svc:request/svc:newField", c),
                Map.of("/svc:request/svc:newField", e));

        assertThat(out.divergences).hasSize(1);
        assertThat(out.divergences.get(0).kind()).isEqualTo("missing_vendor");
        assertThat(out.divergences.get(0).impact()).isEqualTo("medium");
    }

    @Test
    void missingVendorWithoutEmpiricalSupportFallsBackToLowImpact() {
        var c = info("xsd:string", null, "java.lang.String", null);
        var e = ElementInfo.absent();          // not present in corpus

        var out = merger.merge(
                Map.of(),
                Map.of("/svc:request/svc:codeOnly", c),
                Map.of());

        assertThat(out.divergences).hasSize(1);
        assertThat(out.divergences.get(0).impact()).isEqualTo("low");
    }

    @Test
    void formatDifferenceFiresWhenVendorIsXsdDateButCorpusHasMmDdYyyy() {
        var v = info("xsd:date", null, null, null);
        var c = info("xsd:date", null, "java.time.LocalDate", null);
        var e = info("string", "MM/dd/yyyy", null, null);

        var out = merger.merge(
                Map.of("/svc:request/svc:tradeDate", v),
                Map.of("/svc:request/svc:tradeDate", c),
                Map.of("/svc:request/svc:tradeDate", e));

        assertThat(out.divergences).hasSize(1);
        assertThat(out.divergences.get(0).kind()).isEqualTo("format_difference");
        assertThat(out.divergences.get(0).impact()).isEqualTo("high");
    }

    @Test
    void typeLenienceFiresWhenCodeUsesAdapterAndEmpiricalIsString() {
        var v = info("xsd:date", null, null, null);
        var c = info("xsd:date", null, "java.util.Date", "BroadridgeDateAdapter");
        var e = info("string", "yyyy-MM-dd", null, null);

        var out = merger.merge(
                Map.of("/svc:request/svc:settleDate", v),
                Map.of("/svc:request/svc:settleDate", c),
                Map.of("/svc:request/svc:settleDate", e));

        assertThat(out.divergences).hasSize(1);
        assertThat(out.divergences.get(0).kind()).isEqualTo("type_lenience");
    }

    @Test
    void enumPromotionFiresWhenVendorStringMeetsCorpusEnum() {
        var v = info("xsd:string", null, null, null);
        var e = info("enum", null, null, null);
        e.enumValues = java.util.List.of("ACTIVE", "INACTIVE", "ARCHIVED");

        var out = merger.merge(
                Map.of("/svc:request/svc:status", v),
                Map.of(),
                Map.of("/svc:request/svc:status", e));

        assertThat(out.divergences).hasSize(1);
        assertThat(out.divergences.get(0).kind()).isEqualTo("enum_promotion");
        assertThat(out.divergences.get(0).impact()).isEqualTo("medium");
    }

    @Test
    void syntheticUnderscoreClassPathsAreFilteredOutEntirely() {
        var c = info("xsd:string", null, "Money", null);

        var out = merger.merge(
                Map.of(),
                Map.of(
                        "/svc:request/svc:amount", c,
                        "/svc:request._class",   c     // synthetic
                ),
                Map.of());

        assertThat(out.elements).extracting("path")
                .doesNotContain("/svc:request._class")
                .contains("/svc:request/svc:amount");
    }

    @Test
    void mergedElementListIsSortedDeterministicallyByPath() {
        var c = info("xsd:string", null, "java.lang.String", null);
        Map<String, ElementInfo> code = new LinkedHashMap<>();
        code.put("/svc:request/svc:zeta", c);
        code.put("/svc:request/svc:alpha", c);
        code.put("/svc:request/svc:mid", c);

        var out = merger.merge(Map.of(), code, Map.of());

        assertThat(out.elements).extracting("path")
                .containsExactly(
                        "/svc:request/svc:alpha",
                        "/svc:request/svc:mid",
                        "/svc:request/svc:zeta");
    }

    /* ---------------- helpers ---------------- */

    private static ElementInfo info(String type, String format, String javaType, String adapter) {
        ElementInfo i = new ElementInfo();
        i.present  = true;
        i.type     = type;
        i.format   = format;
        i.javaType = javaType;
        i.adapter  = adapter;
        return i;
    }
}
