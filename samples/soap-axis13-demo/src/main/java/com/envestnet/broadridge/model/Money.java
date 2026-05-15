package com.envestnet.broadridge.model;

import java.io.Serializable;
import java.math.BigDecimal;

public class Money implements Serializable {
    private BigDecimal value;
    private String currency;       // ISO 4217 — USD, EUR, ...

    public Money() {}
    public Money(BigDecimal v, String c) { this.value = v; this.currency = c; }

    public BigDecimal getValue() { return value; }
    public void setValue(BigDecimal v) { this.value = v; }

    public String getCurrency() { return currency; }
    public void setCurrency(String v) { this.currency = v; }
}
