package com.ecommerce.platform.common.observability;

import java.util.Objects;

public record BusinessProcessDefinition(String domain, String status) {

    public BusinessProcessDefinition {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(status, "status");
    }
}
