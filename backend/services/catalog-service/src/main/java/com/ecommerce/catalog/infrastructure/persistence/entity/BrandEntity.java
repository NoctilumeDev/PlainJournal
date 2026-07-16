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
@TableName("catalog_brand")
public class BrandEntity {
    @TableId
    private Long id;
    private String name;
    private String slug;
    private String logoObjectKey;
    private String status;
    @Version
    private Integer version;
    private Instant createdAt;
    private Instant updatedAt;
}
