package com.envestnet.atlas.recon.synth;

import com.envestnet.atlas.recon.domain.DecisionRow;
import com.envestnet.atlas.recon.domain.ElementRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Synthesizes an Authoritative WSDL from the merged element set + the human-
 * resolved decisions. Phase 1d uses a deliberately simple emitter:
 *
 *   - operations are derived from the project's known type roots
 *     (AllocationRequest, *Return, …) — those are the wrappers
 *   - each accepted decision contributes an element to the corresponding
 *     complexType, picking the resolved/agent action's type
 *   - escalated/rejected decisions fall back to xsd:string (safest)
 *
 * The output is provenance-stamped with a leading XML comment so a reviewer
 * can trace each declaration back to a decision id.
 */
public class AuthoritativeWsdl {
    private static final Logger log = LoggerFactory.getLogger(AuthoritativeWsdl.class);

    private static final String SERVICE_NS = "http://broadridge.com/services/mf";
    private static final String TYPES_NS   = "http://broadridge.com/services/mf/types";

    private final ObjectMapper json = new ObjectMapper();

    public String emit(UUID projectId, List<ElementRow> elements, List<DecisionRow> decisions) {
        return emit(projectId, elements, decisions, List.of());
    }

    /**
     * Phase-1d's heuristic for operation discovery — looking at
     * decisions for types ending in {@code Return} — only fires on the
     * Broadridge-shaped sample (AllocationReturn, etc.). Real-world
     * codebases like Apache WS-I have bare field-level decisions and
     * yield no operations, which makes the synthesised WSDL incomplete
     * and wsimport produce zero Java files.
     *
     * <p>The {@code opNames} override accepts the canonical operation
     * names from the project's arch run (the agent already extracted
     * them — see {@code arch.operation}). When supplied, every named
     * operation gets a message / portType binding referencing the
     * synthesised request type, so wsimport always has something to
     * generate from.</p>
     */
    public String emit(UUID projectId, List<ElementRow> elements, List<DecisionRow> decisions,
                       List<String> opNames) {
        Map<String, ChosenField> chosen = new LinkedHashMap<>();
        Map<String, DecisionRow> byPath = new LinkedHashMap<>();
        for (DecisionRow d : decisions) byPath.put(d.path(), d);

        for (ElementRow e : elements) {
            ChosenField f = projectField(e, byPath.get(e.path()));
            if (f != null) chosen.put(e.path(), f);
        }

        // Group by type (everything before the dot).
        Map<String, List<ChosenField>> byType = new LinkedHashMap<>();
        for (ChosenField f : chosen.values()) {
            byType.computeIfAbsent(f.type, k -> new ArrayList<>()).add(f);
        }

        return render(projectId, byType, decisions,
                opNames == null ? List.of() : opNames);
    }

    /* ---------- field projection ---------- */

    private ChosenField projectField(ElementRow e, DecisionRow d) {
        String[] split = e.path().split("\\.", 2);
        if (split.length != 2) return null;
        String typeName = split[0];
        String fieldName = split[1];
        if (fieldName.startsWith("_")) return null;     // synthetic class rows

        Map<String, Object> empiric = readJson(e.empiricView());
        Map<String, Object> vendor  = readJson(e.vendorView());
        Map<String, Object> code    = readJson(e.codeView());

        ChosenField f = new ChosenField();
        f.type = typeName;
        f.field = fieldName;
        f.namespace = TYPES_NS;
        f.minOccurs = 0;          // permissive default

        // Decision-driven projection.
        String action = d == null ? null
                : (d.chosenAction() != null ? d.chosenAction() : d.agentAction());
        boolean rejected = d != null && "rejected".equals(d.resolution());
        if (rejected || "escalate".equals(action)) {
            f.xsdType = "xsd:string";
            f.note = d == null ? "no decision yet" : "escalated to SME";
            f.decisionId = d == null ? null : d.id().toString();
            return f;
        }

        // Empirical and code views shape the type.
        String empiricType = (String) empiric.get("type");
        String empiricFmt  = (String) empiric.get("format");
        @SuppressWarnings("unchecked")
        List<String> enumVals = (List<String>) empiric.get("enum");
        String adapter     = (String) code.get("adapter");
        String javaType    = (String) code.get("javaType");

        if ("promote_to_enum".equals(action) && enumVals != null && !enumVals.isEmpty()) {
            f.xsdType = "xsd:string";
            f.enumValues = enumVals;
            f.note = "promoted to closed enum from empirical observation";
        } else if (adapter != null && empiricFmt != null && !"yyyy-MM-dd".equals(empiricFmt)) {
            // Date adapter case — preserve the legacy MM/dd/yyyy format on the wire.
            f.xsdType = "xsd:string";
            f.format = empiricFmt;
            f.adapter = adapter;
            f.note = "preserve legacy date format via adapter";
        } else if ("xsd:decimal".equals(empiricType)) {
            f.xsdType = "xsd:decimal";
        } else if (vendor.get("type") != null) {
            f.xsdType = qualify(String.valueOf(vendor.get("type")));
        } else if (empiricType != null && !"complex".equals(empiricType)) {
            f.xsdType = empiricType.startsWith("xsd:") ? empiricType : "xsd:string";
        } else if (empiric.get("present") instanceof Boolean b && b
                && "complex".equals(empiricType)) {
            // Reference a sibling complexType; this happens for nested structures
            // like Money. The reference resolves to a complexType we'll also emit.
            f.xsdType = "tns:" + capitalize(fieldName);
            f.complex = true;
        } else {
            f.xsdType = "xsd:string";
        }

        f.action = action;
        f.javaType = javaType;
        f.decisionId = d == null ? null : d.id().toString();
        return f;
    }

    /* ---------- rendering ---------- */

    private String render(UUID projectId,
                          Map<String, List<ChosenField>> byType,
                          List<DecisionRow> decisions,
                          List<String> overrideOpNames) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<!--\n");
        sb.append("  Atlas Migrate · Authoritative WSDL\n");
        sb.append("  Project:  ").append(projectId).append('\n');
        sb.append("  Synthesised from ").append(decisions.size()).append(" reconciliation decisions.\n");
        sb.append("  Each <xsd:element> below is annotated with the decision id that drove its declaration.\n");
        sb.append("-->\n");
        sb.append("<wsdl:definitions\n");
        sb.append("    xmlns:wsdl=\"http://schemas.xmlsoap.org/wsdl/\"\n");
        sb.append("    xmlns:soap=\"http://schemas.xmlsoap.org/wsdl/soap/\"\n");
        sb.append("    xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\"\n");
        sb.append("    xmlns:tns=\"").append(TYPES_NS).append("\"\n");
        sb.append("    xmlns:svc=\"").append(SERVICE_NS).append("\"\n");
        sb.append("    targetNamespace=\"").append(SERVICE_NS).append("\">\n\n");

        sb.append("  <wsdl:types>\n");
        sb.append("    <xsd:schema targetNamespace=\"").append(TYPES_NS).append("\"\n");
        sb.append("                xmlns:tns=\"").append(TYPES_NS).append("\">\n\n");

        for (Map.Entry<String, List<ChosenField>> entry : byType.entrySet()) {
            String typeName = entry.getKey();
            sb.append("      <xsd:complexType name=\"").append(typeName).append("\">\n");
            sb.append("        <xsd:sequence>\n");
            for (ChosenField f : entry.getValue()) {
                sb.append("          ");
                renderElement(sb, f);
                sb.append('\n');
            }
            sb.append("        </xsd:sequence>\n");
            sb.append("      </xsd:complexType>\n\n");
        }

        // Operation selection.
        //   - If the caller supplied operation names from arch-service,
        //     use those (real-world path).
        //   - Otherwise fall back to the Phase-1d heuristic that
        //     discovers names from *Return-suffixed types (Broadridge
        //     sample path — kept for backward compatibility with the
        //     soap-axis13-demo test fixtures).
        List<String> opNames = !overrideOpNames.isEmpty()
                ? overrideOpNames
                : deriveOperationNames(byType.keySet());

        // The synthesised types are driven by reconciliation decisions
        // and don't include an explicit "*Return" companion. Emit a
        // generic response complex-type whose every operation can
        // reference, so wsimport always produces output wrappers.
        boolean needsGenericResponse = !opNames.isEmpty()
                && byType.keySet().stream().noneMatch(t -> t.endsWith("Return"));
        if (needsGenericResponse) {
            sb.append("      <xsd:complexType name=\"GenericResponse\">\n");
            sb.append("        <xsd:sequence>\n");
            sb.append("          <xsd:element name=\"status\" minOccurs=\"0\" type=\"xsd:string\"/>\n");
            sb.append("          <xsd:element name=\"correlationId\" minOccurs=\"0\" type=\"xsd:string\"/>\n");
            sb.append("        </xsd:sequence>\n");
            sb.append("      </xsd:complexType>\n\n");
        }

        // Pick the request type to reference from each operation's wrapper.
        // Prefer a type named "Request" if present (the canonical decision
        // shape), otherwise the first type in byType, otherwise emit a
        // tiny placeholder so the WSDL stays valid.
        String requestType = byType.containsKey("Request")
                ? "Request"
                : byType.keySet().stream().findFirst().orElse(null);
        if (!opNames.isEmpty() && requestType == null) {
            sb.append("      <xsd:complexType name=\"GenericRequest\"><xsd:sequence/></xsd:complexType>\n\n");
            requestType = "GenericRequest";
        }

        // Top-level <xsd:element> wrappers per operation. Document/literal
        // operations require the message parts to reference these.
        for (String op : opNames) {
            sb.append("      <xsd:element name=\"").append(op).append("\" type=\"tns:").append(requestType).append("\"/>\n");
            String responseType = byType.containsKey(capitalize(op) + "Return")
                    ? capitalize(op) + "Return"
                    : "GenericResponse";
            sb.append("      <xsd:element name=\"").append(op).append("Response\" type=\"tns:")
              .append(responseType).append("\"/>\n");
        }

        sb.append("    </xsd:schema>\n");
        sb.append("  </wsdl:types>\n\n");

        // Operation messages
        emitOperations(sb, opNames);

        sb.append("</wsdl:definitions>\n");
        return sb.toString();
    }

    private void renderElement(StringBuilder sb, ChosenField f) {
        sb.append("<!-- decision=").append(f.decisionId == null ? "n/a" : f.decisionId);
        if (f.note != null) sb.append(" · ").append(f.note);
        sb.append(" -->\n          ");
        sb.append("<xsd:element name=\"").append(f.field).append("\"");
        sb.append(" minOccurs=\"").append(f.minOccurs).append("\"");
        if (f.enumValues == null) {
            sb.append(" type=\"").append(f.xsdType).append("\"/>");
        } else {
            sb.append(">\n");
            sb.append("            <xsd:simpleType><xsd:restriction base=\"xsd:string\">\n");
            for (String v : f.enumValues) {
                sb.append("              <xsd:enumeration value=\"").append(escape(v)).append("\"/>\n");
            }
            sb.append("            </xsd:restriction></xsd:simpleType>\n");
            sb.append("          </xsd:element>");
        }
    }

    private List<String> deriveOperationNames(Set<String> typeNames) {
        List<String> opNames = new ArrayList<>();
        for (String t : typeNames) {
            if (t.endsWith("Return")) {
                String op = t.substring(0, t.length() - "Return".length());
                op = lowerInitial(op);
                opNames.add(op);
            }
        }
        return opNames;
    }

    private void emitOperations(StringBuilder sb, List<String> opNames) {
        if (opNames.isEmpty()) return;

        // Messages — reference the top-level wrapper elements via `element=`,
        // not types via `type=`, as document/literal style requires.
        for (String op : opNames) {
            sb.append("  <wsdl:message name=\"").append(op).append("Request\">\n");
            sb.append("    <wsdl:part name=\"parameters\" element=\"tns:").append(op).append("\"/>\n");
            sb.append("  </wsdl:message>\n");
            sb.append("  <wsdl:message name=\"").append(op).append("Response\">\n");
            sb.append("    <wsdl:part name=\"parameters\" element=\"tns:").append(op).append("Response\"/>\n");
            sb.append("  </wsdl:message>\n\n");
        }
        // PortType
        sb.append("  <wsdl:portType name=\"AllocationServicePortType\">\n");
        for (String op : opNames) {
            sb.append("    <wsdl:operation name=\"").append(op).append("\">\n");
            sb.append("      <wsdl:input  message=\"svc:").append(op).append("Request\"/>\n");
            sb.append("      <wsdl:output message=\"svc:").append(op).append("Response\"/>\n");
            sb.append("    </wsdl:operation>\n");
        }
        sb.append("  </wsdl:portType>\n\n");
        // Binding
        sb.append("  <wsdl:binding name=\"AllocationServiceSoap\" type=\"svc:AllocationServicePortType\">\n");
        sb.append("    <soap:binding style=\"document\"\n");
        sb.append("                  transport=\"http://schemas.xmlsoap.org/soap/http\"/>\n");
        for (String op : opNames) {
            sb.append("    <wsdl:operation name=\"").append(op).append("\">\n");
            sb.append("      <soap:operation soapAction=\"").append(op).append("\"/>\n");
            sb.append("      <wsdl:input><soap:body use=\"literal\"/></wsdl:input>\n");
            sb.append("      <wsdl:output><soap:body use=\"literal\"/></wsdl:output>\n");
            sb.append("    </wsdl:operation>\n");
        }
        sb.append("  </wsdl:binding>\n\n");
        // Service
        sb.append("  <wsdl:service name=\"AllocationService\">\n");
        sb.append("    <wsdl:port name=\"AllocationServicePort\" binding=\"svc:AllocationServiceSoap\">\n");
        sb.append("      <soap:address location=\"http://broadridge.example.com/services/AllocationService\"/>\n");
        sb.append("    </wsdl:port>\n");
        sb.append("  </wsdl:service>\n");
    }

    /* ---------- utility ---------- */

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String s) {
        if (s == null || s.isEmpty()) return Map.of();
        try { return json.readValue(s, Map.class); }
        catch (Exception e) { return Map.of(); }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String lowerInitial(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /**
     * Vendor types arrive prefix-stripped (the parser drops "xsd:" so a downstream
     * comparator can match "xsd:string" against "string"). Re-add a namespace
     * prefix when emitting so wsimport's schema compiler can resolve the type:
     *   - built-in lowercase XSD types → xsd:
     *   - already-prefixed values     → kept as-is
     *   - project-local complex types → tns:
     */
    private static String qualify(String type) {
        if (type == null || type.isEmpty()) return "xsd:string";
        if (type.contains(":")) return type;
        if (Character.isLowerCase(type.charAt(0))) return "xsd:" + type;
        return "tns:" + type;
    }

    /* ---------- DTO ---------- */

    private static class ChosenField {
        String type;
        String field;
        String namespace;
        String xsdType;
        int minOccurs = 0;
        List<String> enumValues;
        String adapter;
        String javaType;
        String format;
        String action;
        String note;
        String decisionId;
        boolean complex;
    }
}
