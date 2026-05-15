package com.envestnet.broadridge.model;

import java.io.Serializable;
import java.util.Date;

public class AllocationResponse implements Serializable {
    private String allocationId;
    private AllocationStatus status;
    private Money executedAmount;
    private Date settlementDate;     // also serialized via BroadridgeDateAdapter
    private String confirmationNumber;

    public AllocationResponse() {}

    public String getAllocationId() { return allocationId; }
    public void setAllocationId(String v) { this.allocationId = v; }

    public AllocationStatus getStatus() { return status; }
    public void setStatus(AllocationStatus v) { this.status = v; }

    public Money getExecutedAmount() { return executedAmount; }
    public void setExecutedAmount(Money v) { this.executedAmount = v; }

    public Date getSettlementDate() { return settlementDate; }
    public void setSettlementDate(Date v) { this.settlementDate = v; }

    public String getConfirmationNumber() { return confirmationNumber; }
    public void setConfirmationNumber(String v) { this.confirmationNumber = v; }
}
