package com.ecommerce.fulfillment.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@TableName("fulfillment_status_history")
public class FulfillmentStatusHistoryEntity {
    @TableId
    private Long id;
    private Long fulfillmentId;
    private String fromStatus;
    private String toStatus;
    private String command;
    private String reason;
    private String operatorType;
    private String operatorId;
    private Instant createdAt;
}
