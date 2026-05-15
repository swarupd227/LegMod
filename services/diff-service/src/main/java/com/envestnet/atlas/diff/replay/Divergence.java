package com.envestnet.atlas.diff.replay;

/** A single difference between the legacy wire form and the regenerated form. */
public class Divergence {
    public String kind;            // namespace_prefix | element_ordering | date_format | type_precision
                                   // | missing_element | extra_element | enum_miss | value_diff | fault_diff
    public String bucket;          // benign | amber | red — heuristic, refined by the agent
    public String xpath;           // canonical xpath of the offending node
    public String legacyValue;     // optional
    public String newValue;        // optional
    public String humanSummary;    // one-liner for the diff explorer

    public static Divergence of(String kind, String bucket, String xpath,
                                String legacy, String fresh, String summary) {
        Divergence d = new Divergence();
        d.kind = kind; d.bucket = bucket; d.xpath = xpath;
        d.legacyValue = legacy; d.newValue = fresh; d.humanSummary = summary;
        return d;
    }
}
