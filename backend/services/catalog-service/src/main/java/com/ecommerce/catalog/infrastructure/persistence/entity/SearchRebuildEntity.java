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
@TableName("catalog_search_rebuild")
public class SearchRebuildEntity {
    @TableId
    private Long id;
    private String commandId;
    private Long operatorId;
    private String reason;
    private String requestHash;
    private String status;
    private String targetIndex;
    private Integer attempts;
    private Long indexedCount;
    private Instant claimedAt;
    private String claimOwner;
    private Instant claimUntil;
    private Instant startedAt;
    private Instant completedAt;
    private String lastError;
    private Instant createdAt;
    private Instant updatedAt;
}
