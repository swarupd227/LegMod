package com.envestnet.atlas.recon.schema;

import java.util.*;

/**
 * Produces a unified set of element rows + a list of divergences (decisions
 * to surface) by walking the union of paths across the three input views.
 *
 * Divergence kinds — Phase 1c covers the big ones:
 *   format_difference  vendor and empirical disagree on a value's lexical shape
 *   type_lenience      empirical wire shape is laxer than the vendor type
 *   enum_promotion     empirical values are enum-shaped, vendor declares string
 *   missing_vendor     code/empirical see it; vendor's WSDL doesn't declare it
 *   rename             same field name, different namespace (deferred)
 */
public class Merger {

    public static class Output {
        public List<ElementRecord> elements = new ArrayList<>();
        public List<DivergenceRecord> divergences = new ArrayList<>();
    }
    public record ElementRecord(String path, ElementInfo vendor, ElementInfo code, ElementInfo empiric) {}
    public record DivergenceRecord(
            String path, String kind, String impact,
            ElementInfo vendor, ElementInfo code, ElementInfo empiric,
            String elementId  // attached after the element row is persisted
    ) {}

    public Output merge(Map<String, ElementInfo> vendor,
                        Map<String, ElementInfo> code,
                        Map<String, ElementInfo> empirical) {
        Output out = new Output();
        Set<String> paths = new TreeSet<>();
        paths.addAll(vendor.keySet());
        paths.addAll(code.keySet());
        paths.addAll(empirical.keySet());

        for (String path : paths) {
            // Skip the synthetic "_class" rows — they're code-only metadata.
            if (path.endsWith("._class")) continue;

            ElementInfo v = vendor.getOrDefault(path,    ElementInfo.absent());
            ElementInfo c = code.getOrDefault(path,      ElementInfo.absent());
            ElementInfo e = empirical.getOrDefault(path, ElementInfo.absent());

            out.elements.add(new ElementRecord(path, v, c, e));

            DivergenceRecord d = classify(path, v, c, e);
            if (d != null) out.divergences.add(d);
        }
        return out;
    }

    private DivergenceRecord classify(String path, ElementInfo v, ElementInfo c, ElementInfo e) {
        boolean vendorPresent  = v.present;
        boolean codePresent    = c.present;
        boolean empiricPresent = e.present;

        // Skip rows where only the vendor declares the field — those happen
        // when the WSDL is more granular than what the corpus exercised.
        if (vendorPresent && !codePresent && !empiricPresent) return null;

        // Empirical or code present, vendor missing.
        if (!vendorPresent && (codePresent || empiricPresent)) {
            String impact = (e.observed != null && e.observed > 0) ? "medium" : "low";
            return new DivergenceRecord(path, "missing_vendor", impact, v, c, e, null);
        }

        // Format mismatch: vendor says xsd:date but empirical observes string in MM/dd/yyyy.
        if (vendorPresent && empiricPresent
                && "xsd:date".equals(v.type)
                && e.format != null && !"yyyy-MM-dd".equals(e.format)) {
            return new DivergenceRecord(path, "format_difference", "high", v, c, e, null);
        }

        // Code declares xsd:date with adapter, vendor xsd:date, empirical string.
        if (vendorPresent && codePresent && empiricPresent
                && "xsd:date".equals(v.type)
                && c.adapter != null && !c.adapter.isBlank()
                && "string".equals(e.type)) {
            return new DivergenceRecord(path, "type_lenience", "high", v, c, e, null);
        }

        // Enum promotion: vendor xsd:string, empirical observed a small enum.
        if (vendorPresent && empiricPresent
                && ("xsd:string".equals(v.type) || v.type == null)
                && "enum".equals(e.type)) {
            return new DivergenceRecord(path, "enum_promotion", "medium", v, c, e, null);
        }

        // Type lenience: vendor decimal, empirical decimal but code uses Money wrapper.
        if (vendorPresent && empiricPresent && codePresent
                && c.javaType != null && c.javaType.toLowerCase().contains("money")
                && "xsd:decimal".equals(e.type)) {
            return new DivergenceRecord(path, "type_lenience", "low", v, c, e, null);
        }

        return null;
    }
}
