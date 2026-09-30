package com.ecommerce.catalog.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.catalog.infrastructure.persistence.entity.ProductMediaEntity;
import org.apache.ibatis.annotations.Insert;

public interface ProductMediaMapper extends BaseMapper<ProductMediaEntity> {

    @Insert("""
            INSERT IGNORE INTO product_media
                (id, spu_id, sku_id, object_key, mime_type, size_bytes, sort_order, created_at)
            VALUES
                (#{id}, #{spuId}, #{skuId}, #{objectKey}, #{mimeType}, #{sizeBytes}, #{sortOrder}, #{createdAt})
            """)
    int insertIgnore(ProductMediaEntity entity);
}
