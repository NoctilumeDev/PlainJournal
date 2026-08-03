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
@TableName("catalog_search_reconciliation")
public class SearchReconciliationEntity {
    @TableId
    private Long id;
    private Long productId;
    private String issueType;
    private String status;
    private Long mysqlRevision;
    private Long indexRevision;
    private Integer occurrences;
    private Instant firstDetectedAt;
    private Instant lastDetectedAt;
    private Instant resolvedAt;
}
