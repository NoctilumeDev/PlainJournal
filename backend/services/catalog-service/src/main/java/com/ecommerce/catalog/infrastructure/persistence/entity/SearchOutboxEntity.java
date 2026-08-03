package com.ecommerce.catalog.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@TableName("catalog_search_outbox")
public class SearchOutboxEntity {
    @TableId
    private String id;
    private Long productId;
    private Long targetRevision;
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
