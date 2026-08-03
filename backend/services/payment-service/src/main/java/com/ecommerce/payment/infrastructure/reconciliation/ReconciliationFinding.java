package com.ecommerce.payment.infrastructure.reconciliation;

public record ReconciliationFinding(
        String domain,
        String referenceNo,
        String issueType
) {

    public String key() {
        return domain + "\u0000" + referenceNo + "\u0000" + issueType;
    }
}
