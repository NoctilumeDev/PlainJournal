package com.ecommerce.platform.common.observability;

public interface ConsumerFailureRetryHandler {

    String consumerGroup();

    void retry(String rawPayload) throws Exception;

    default boolean isTerminal(Exception exception) {
        return exception instanceof IllegalArgumentException;
    }
}
