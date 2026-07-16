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
@TableName("cart_item")
public class CartItemEntity {
    @TableId
    private Long id;
    private Long userId;
    private Long productId;
    private Long skuId;
    private Long quantity;
    private Boolean selected;
    private Instant createdAt;
    private Instant updatedAt;
}
