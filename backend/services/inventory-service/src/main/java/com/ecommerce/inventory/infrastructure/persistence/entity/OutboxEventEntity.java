package com.ecommerce.inventory.infrastructure.persistence.entity;

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
    private Long id;
    private String eventId;
    private String eventType;
    private String aggregateType;
    private String aggregateId;
    private Integer aggregateVersion;
    private String payload;
    private String status;
    private Integer attempts;
    private Instant nextAttemptAt;
    private String lastError;
    private Instant publishedAt;
    private Instant createdAt;
    private Instant updatedAt;
}
