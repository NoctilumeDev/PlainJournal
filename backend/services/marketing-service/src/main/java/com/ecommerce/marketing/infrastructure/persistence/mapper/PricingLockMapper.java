package com.ecommerce.marketing.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.marketing.infrastructure.persistence.entity.PricingLockEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface PricingLockMapper extends BaseMapper<PricingLockEntity> {

    @Insert("""
            INSERT IGNORE INTO pricing_lock
                (id, lock_no, order_no, user_id, request_hash, original_amount,
                 discount_amount, payable_amount, status, locked_at, released_at,
                 redeemed_at, version, created_at, updated_at)
            VALUES
                (#{entity.id}, #{entity.lockNo}, #{entity.orderNo}, #{entity.userId},
                 #{entity.requestHash}, #{entity.originalAmount}, #{entity.discountAmount},
                 #{entity.payableAmount}, #{entity.status}, #{entity.lockedAt},
                 #{entity.releasedAt}, #{entity.redeemedAt}, #{entity.version},
                 #{entity.createdAt}, #{entity.updatedAt})
            """)
    int insertIfAbsent(@Param("entity") PricingLockEntity entity);

    @Select("SELECT * FROM pricing_lock WHERE order_no = #{orderNo} FOR UPDATE")
    PricingLockEntity selectByOrderNoForUpdate(@Param("orderNo") String orderNo);
}
