package com.envestnet.atlas.recon.schema;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The structural facts about an element from one source. Fields not known
 * by that source stay null. Three of these — vendor / code / empirical — are
 * folded together by the merge engine.
 */
public class ElementInfo {
    public boolean present;
    public String type;             // xsd:date | xsd:string | string | enum | decimal | …
    public String format;           // free-form, e.g. MM/dd/yyyy
    public String javaType;         // for code-derived view
    public String adapter;          // adapter FQN, if used
    public Integer minOccurs;
    public Integer maxOccurs;
    public Long observed;           // empirical: how many envelopes carried this path
    public java.util.List<String> sampleValues;
    public java.util.List<String> enumValues;
    public String namespace;

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("present", present);
        if (type != null)        m.put("type", type);
        if (format != null)      m.put("format", format);
        if (javaType != null)    m.put("javaType", javaType);
        if (adapter != null)     m.put("adapter", adapter);
        if (minOccurs != null)   m.put("minOccurs", minOccurs);
        if (maxOccurs != null)   m.put("maxOccurs", maxOccurs);
        if (observed != null)    m.put("observed", observed);
        if (namespace != null)   m.put("namespace", namespace);
        if (sampleValues != null && !sampleValues.isEmpty()) m.put("samples", sampleValues);
        if (enumValues != null && !enumValues.isEmpty())     m.put("enum", enumValues);
        return m;
    }

    public static ElementInfo absent() {
        ElementInfo i = new ElementInfo();
        i.present = false;
        return i;
    }
}
