package com.ecommerce.payment.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@TableName("reconciliation_record")
public class ReconciliationRecordEntity {
    @TableId
    private Long id;
    private String domain;
    private String referenceNo;
    private String issueType;
    private String status;
    private Integer occurrences;
    private Instant firstDetectedAt;
    private Instant lastDetectedAt;
    private Instant resolvedAt;
}
