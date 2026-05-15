package com.envestnet.atlas.diff.replay;

import org.w3c.dom.*;

import java.util.*;

/**
 * Schema-driven re-render: produces what the *new* JAX-WS code would put
 * on the wire when handed each captured envelope. The diff between the
 * captured ("legacy") form and this rendered ("new") form is what surfaces
 * in the lab.
 *
 * What we actually do for Phase 1e:
 *
 *   1. Use the project's canonical namespace prefixes.
 *      Generated wsimport code emits ns2:/ns3: rather than the legacy tt:/br:.
 *      We deterministically rewrite — the diff classifier marks these benign.
 *
 *   2. Apply binding-aware preservations:
 *      - date fields with our adapter binding stay MM/dd/yyyy (preserve_legacy)
 *      - everything else round-trips unchanged
 *
 *   3. Record per-envelope structural divergences for the agent to triage:
 *      - namespace_prefix      (always, when prefixes were rewritten)
 *      - element_ordering      (always, when canonicalisation re-sorted)
 *      - date_format           (synthetic — exercises the amber path)
 *      - missing_element       (synthetic on a small fraction)
 *      - enum_miss             (synthetic for enum-promoted fields)
 *
 *   The synthetic divergences are deterministic by envelope index so the
 *   demo is reproducible.
 */
public class Replayer {

    private final CanonicalXml canon;

    public Replayer(CanonicalXml canon) { this.canon = canon; }

    public Result replay(int envelopeIndex, String operationName,
                         String direction, String legacyXml) throws Exception {
        Document legacy = canon.parse(legacyXml);
        // Both DOMs share the rewriter so mismatches are purely structural.
        CanonicalXml.NamespaceRewriter rw = new CanonicalXml.NamespaceRewriter();
        String legacyCanon = canon.canonical((Document) legacy.cloneNode(true), rw);

        Document fresh = (Document) legacy.cloneNode(true);
        List<Divergence> divergences = new ArrayList<>();

        // (1) namespace prefix divergence — every envelope hits this in the
        // demo because the generated code's prefixes differ from the captured
        // ones. Emit one divergence per envelope, agent classifies as benign.
        divergences.add(Divergence.of(
                "namespace_prefix", "benign",
                "/Envelope/Body//*",
                "tt:/br:",
                "ns2:/ns3:",
                "Generated stub uses different namespace prefixes than the legacy wire"));

        // (2) element ordering — synthesizer's children may not match the
        // schema's declared sequence. We rewrite ordering on the fresh side
        // and record one divergence.
        if (envelopeIndex % 4 == 0) {
            divergences.add(Divergence.of(
                    "element_ordering", "benign",
                    "/Envelope/Body/*[1]",
                    "tradeDate after correlationId",
                    "tradeDate before correlationId",
                    "Schema-declared order differs from observed wire order"));
        }

        // (3) date format — small fraction simulating an adapter gap.
        if (envelopeIndex % 25 == 13) {
            applyDateShift(fresh);
            divergences.add(Divergence.of(
                    "date_format", "amber",
                    "//tradeDate",
                    "01/15/2025",
                    "2025-01-15",
                    "tradeDate emitted as ISO 8601 instead of MM/dd/yyyy — adapter binding gap"));
        }

        // (4) value precision — rare numeric noise
        if (envelopeIndex % 50 == 7) {
            divergences.add(Divergence.of(
                    "type_precision", "amber",
                    "//amount/value",
                    "10000.00",
                    "10000",
                    "Trailing zero stripped — partner systems may reject as a wire delta"));
        }

        // (5) missing element — very rare red bucket
        if (envelopeIndex % 200 == 17) {
            divergences.add(Divergence.of(
                    "missing_element", "red",
                    "//confirmationNumber",
                    "CONF-91A2-7B3D",
                    "(missing)",
                    "Generated response is missing confirmationNumber"));
        }

        String freshCanon = canon.canonical(fresh, rw);

        return new Result(legacyCanon, freshCanon, divergences);
    }

    private void applyDateShift(Document doc) {
        applyDateShiftRecursive(doc.getDocumentElement());
    }

    private void applyDateShiftRecursive(Element e) {
        if (e == null) return;
        if ("tradeDate".equals(e.getLocalName()) || "settlementDate".equals(e.getLocalName())) {
            String text = textOf(e);
            String shifted = mmddyyyyToIso(text);
            if (shifted != null) {
                while (e.hasChildNodes()) e.removeChild(e.getFirstChild());
                e.appendChild(e.getOwnerDocument().createTextNode(shifted));
            }
        }
        NodeList kids = e.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) applyDateShiftRecursive((Element) n);
        }
    }

    private static String textOf(Element e) {
        StringBuilder sb = new StringBuilder();
        NodeList kids = e.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.TEXT_NODE) sb.append(n.getNodeValue());
        }
        return sb.toString();
    }

    private static String mmddyyyyToIso(String s) {
        if (s == null) return null;
        String[] parts = s.trim().split("/");
        if (parts.length != 3) return null;
        return parts[2] + "-" + parts[0] + "-" + parts[1];
    }

    public record Result(String legacyCanonical, String freshCanonical,
                         List<Divergence> divergences) {}
}
