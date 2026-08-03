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
@TableName("shipment_latest_position")
public class ShipmentLatestPositionEntity {
    @TableId
    private Long fulfillmentId;
    private String fulfillmentNo;
    private Long traceId;
    private String externalEventId;
    private String nodeType;
    private String locationName;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private Instant occurredAt;
    private Instant updatedAt;
}
