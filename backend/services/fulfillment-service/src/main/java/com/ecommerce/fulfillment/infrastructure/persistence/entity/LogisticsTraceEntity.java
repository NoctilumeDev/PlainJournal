package com.ecommerce.fulfillment.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@TableName("logistics_trace")
public class LogisticsTraceEntity {
    @TableId
    private Long id;
    private Long fulfillmentId;
    private String carrier;
    private String trackingNo;
    private String externalEventId;
    private String requestHash;
    private String nodeType;
    private String description;
    private String locationName;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private Instant occurredAt;
    private Instant createdAt;
}
