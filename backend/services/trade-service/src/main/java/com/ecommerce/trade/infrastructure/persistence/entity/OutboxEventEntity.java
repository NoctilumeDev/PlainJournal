package com.ecommerce.trade.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@TableName("outbox_event")
public class OutboxEventEntity {
    @TableId
    private String id;
    private String eventType;
    private String aggregateType;
    private String aggregateId;
    private Integer aggregateVersion;
    private String destinationTopic;
    private String payload;
    private String status;
    private Integer attempts;
    private Instant nextAttemptAt;
    private Instant claimedAt;
    private String claimOwner;
    private Instant claimUntil;
    private Instant publishedAt;
    private String lastError;
    private Instant createdAt;
    private Instant updatedAt;
}
