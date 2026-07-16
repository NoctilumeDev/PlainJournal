package com.ecommerce.trade.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@TableName("order_benefit_selection")
public class OrderBenefitSelectionEntity {
    @TableId
    private Long id;
    private Long orderId;
    private String benefitNo;
    private Instant createdAt;
}
