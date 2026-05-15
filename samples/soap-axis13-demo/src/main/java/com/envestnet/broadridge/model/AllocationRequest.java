package com.envestnet.broadridge.model;

import java.io.Serializable;
import java.util.Date;

public class AllocationRequest implements Serializable {
    private String accountId;
    private String fundSymbol;
    private Money amount;
    private Date tradeDate;          // serialized as MM/dd/yyyy via BroadridgeDateAdapter
    private String accountType;      // RETAIL | INSTITUTIONAL | RETIREMENT
    private String correlationId;

    public AllocationRequest() {}

    public String getAccountId() { return accountId; }
    public void setAccountId(String v) { this.accountId = v; }

    public String getFundSymbol() { return fundSymbol; }
    public void setFundSymbol(String v) { this.fundSymbol = v; }

    public Money getAmount() { return amount; }
    public void setAmount(Money v) { this.amount = v; }

    public Date getTradeDate() { return tradeDate; }
    public void setTradeDate(Date v) { this.tradeDate = v; }

    public String getAccountType() { return accountType; }
    public void setAccountType(String v) { this.accountType = v; }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String v) { this.correlationId = v; }
}
