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
@TableName("catalog_search_rebuild_recovery_audit")
public class SearchRebuildRecoveryAuditEntity {
    @TableId
    private Long id;
    private String commandId;
    private Long rebuildId;
    private Long operatorId;
    private String reason;
    private String requestHash;
    private String statusBefore;
    private String statusAfter;
    private Instant createdAt;
}
