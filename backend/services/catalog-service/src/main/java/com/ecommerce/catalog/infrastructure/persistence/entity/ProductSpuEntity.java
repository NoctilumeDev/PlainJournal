package com.ecommerce.catalog.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@TableName("product_spu")
public class ProductSpuEntity {
    @TableId
    private Long id;
    private Long categoryId;
    private Long brandId;
    private String title;
    private String subtitle;
    private String description;
    private String status;
    @Version
    private Integer version;
    private Long searchRevision;
    private Instant createdAt;
    private Instant updatedAt;
}
