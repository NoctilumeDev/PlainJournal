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
@TableName("cart_merge_request")
public class CartMergeRequestEntity {
    @TableId
    private Long id;
    private Long userId;
    private String mergeKey;
    private String requestHash;
    private Instant createdAt;
}
