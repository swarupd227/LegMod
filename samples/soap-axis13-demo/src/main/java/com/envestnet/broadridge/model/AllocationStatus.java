package com.envestnet.broadridge.model;

import java.io.Serializable;

/**
 * Custom enum-style class — Axis serializes via EnumSerializerFactory so
 * the wire form is &lt;status&gt;ACCEPTED&lt;/status&gt; rather than
 * &lt;status&gt;0&lt;/status&gt;.
 */
public class AllocationStatus implements Serializable {

    public static final AllocationStatus PENDING   = new AllocationStatus("PENDING");
    public static final AllocationStatus ACCEPTED  = new AllocationStatus("ACCEPTED");
    public static final AllocationStatus PARTIAL   = new AllocationStatus("PARTIAL");
    public static final AllocationStatus EXECUTED  = new AllocationStatus("EXECUTED");
    public static final AllocationStatus REJECTED  = new AllocationStatus("REJECTED");
    public static final AllocationStatus CANCELLED = new AllocationStatus("CANCELLED");
    public static final AllocationStatus FAILED    = new AllocationStatus("FAILED");
    public static final AllocationStatus UNKNOWN   = new AllocationStatus("UNKNOWN");

    private final String value;

    private AllocationStatus(String value) { this.value = value; }
    public String getValue() { return value; }
    public String toString() { return value; }

    public static AllocationStatus fromString(String s) {
        return switch (s) {
            case "PENDING"   -> PENDING;
            case "ACCEPTED"  -> ACCEPTED;
            case "PARTIAL"   -> PARTIAL;
            case "EXECUTED"  -> EXECUTED;
            case "REJECTED"  -> REJECTED;
            case "CANCELLED" -> CANCELLED;
            case "FAILED"    -> FAILED;
            default          -> UNKNOWN;
        };
    }
}
