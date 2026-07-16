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
@TableName("product_media")
public class ProductMediaEntity {
    @TableId
    private Long id;
    private Long spuId;
    private Long skuId;
    private String objectKey;
    private String mimeType;
    private Long sizeBytes;
    private Integer sortOrder;
    private Instant createdAt;
}
