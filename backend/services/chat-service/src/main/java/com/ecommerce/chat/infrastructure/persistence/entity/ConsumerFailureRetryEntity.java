package com.ecommerce.chat.infrastructure.persistence.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
public class ConsumerFailureRetryEntity {

    private String messageId;
    private String consumerGroup;
    private String rawPayload;
    private Integer attempts;
    private Instant nextAttemptAt;
}
