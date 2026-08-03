package com.ecommerce.trade.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.trade.infrastructure.persistence.entity.CartItemEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;

public interface CartItemMapper extends BaseMapper<CartItemEntity> {

    @Select("SELECT CURRENT_TIMESTAMP(3)")
    Instant currentTime();

    @Select("""
            SELECT * FROM cart_item
            WHERE user_id = #{userId} AND sku_id = #{skuId}
            FOR UPDATE
            """)
    CartItemEntity selectForUpdate(@Param("userId") Long userId, @Param("skuId") Long skuId);
}
