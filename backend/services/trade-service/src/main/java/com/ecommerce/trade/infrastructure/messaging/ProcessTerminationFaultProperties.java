package com.ecommerce.trade.infrastructure.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ecommerce.trade.fault-injection")
public record ProcessTerminationFaultProperties(
        boolean enabled,
        ProcessTerminationPoint point,
        String targetEventId,
        int exitCode
) {
    private static final int DEFAULT_EXIT_CODE = 91;

    public ProcessTerminationFaultProperties {
        exitCode = exitCode == 0 ? DEFAULT_EXIT_CODE : exitCode;
        if (exitCode < 1 || exitCode > 255) {
            throw new IllegalArgumentException("fault-injection exitCode must be between 1 and 255");
        }
        if (enabled) {
            if (point == null) {
                throw new IllegalArgumentException("fault-injection point is required when enabled");
            }
            if (targetEventId == null || targetEventId.isBlank() || targetEventId.length() > 128) {
                throw new IllegalArgumentException(
                        "fault-injection targetEventId is required and must not exceed 128 characters");
            }
        }
    }

    public static ProcessTerminationFaultProperties disabled() {
        return new ProcessTerminationFaultProperties(false, null, null, DEFAULT_EXIT_CODE);
    }
}
