package com.ecommerce.trade.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.trade.infrastructure.persistence.entity.AfterSaleOrderEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AfterSaleOrderMapper extends BaseMapper<AfterSaleOrderEntity> {

    @Insert("""
            INSERT IGNORE INTO after_sale_order
                (id, after_sale_no, order_id, order_no, user_id, after_sale_type, status,
                 idempotency_key, request_hash, reason, refund_amount, warehouse_id,
                 reservation_no, version, created_at, updated_at)
            VALUES
                (#{entity.id}, #{entity.afterSaleNo}, #{entity.orderId}, #{entity.orderNo},
                 #{entity.userId}, #{entity.afterSaleType}, #{entity.status},
                 #{entity.idempotencyKey}, #{entity.requestHash}, #{entity.reason},
                 #{entity.refundAmount}, #{entity.warehouseId}, #{entity.reservationNo},
                 #{entity.version}, #{entity.createdAt}, #{entity.updatedAt})
            """)
    int insertIfAbsent(@Param("entity") AfterSaleOrderEntity entity);

    @Select("SELECT * FROM after_sale_order WHERE user_id = #{userId} AND idempotency_key = #{key} FOR UPDATE")
    AfterSaleOrderEntity selectByIdempotencyForUpdate(@Param("userId") Long userId, @Param("key") String key);

    @Select("SELECT * FROM after_sale_order WHERE order_id = #{orderId} FOR UPDATE")
    AfterSaleOrderEntity selectByOrderForUpdate(@Param("orderId") Long orderId);

    @Select("SELECT * FROM after_sale_order WHERE after_sale_no = #{afterSaleNo} FOR UPDATE")
    AfterSaleOrderEntity selectForUpdate(@Param("afterSaleNo") String afterSaleNo);
}
