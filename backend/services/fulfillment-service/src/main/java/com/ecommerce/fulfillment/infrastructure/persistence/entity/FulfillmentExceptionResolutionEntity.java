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
@TableName("fulfillment_exception_resolution")
public class FulfillmentExceptionResolutionEntity {
    @TableId
    private Long id;
    private String commandId;
    private String requestHash;
    private Long fulfillmentId;
    private String fulfillmentNo;
    private String resumeStatus;
    private String operatorId;
    private String reason;
    private Instant createdAt;
}
