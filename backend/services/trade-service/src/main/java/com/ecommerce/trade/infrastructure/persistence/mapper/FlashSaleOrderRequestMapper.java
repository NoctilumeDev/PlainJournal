package com.ecommerce.trade.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.trade.infrastructure.persistence.entity.FlashSaleOrderRequestEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

public interface FlashSaleOrderRequestMapper extends BaseMapper<FlashSaleOrderRequestEntity> {

    @Select("SELECT CURRENT_TIMESTAMP(3)")
    Instant currentTime();

    @Insert("""
            INSERT INTO flash_sale_order_request
                (id, request_token, admission_event_id, request_hash, activity_no,
                 user_id, address_id, product_id, sku_id, sale_price, status,
                 order_no, failure_code, attempts, next_attempt_at, last_error,
                 version, accepted_at, activity_ends_at, completed_at, created_at, updated_at)
            VALUES
                (#{entity.id}, #{entity.requestToken}, #{entity.admissionEventId},
                 #{entity.requestHash}, #{entity.activityNo}, #{entity.userId},
                 #{entity.addressId}, #{entity.productId}, #{entity.skuId},
                 #{entity.salePrice}, #{entity.status}, #{entity.orderNo},
                 #{entity.failureCode}, #{entity.attempts}, #{entity.nextAttemptAt},
                 #{entity.lastError}, #{entity.version}, #{entity.acceptedAt},
                 #{entity.activityEndsAt}, #{entity.completedAt},
                 #{entity.createdAt}, #{entity.updatedAt})
            ON DUPLICATE KEY UPDATE id = id
            """)
    int insertOrLockExisting(@Param("entity") FlashSaleOrderRequestEntity entity);

    @Select("SELECT * FROM flash_sale_order_request WHERE request_token = #{requestToken} FOR UPDATE")
    FlashSaleOrderRequestEntity selectByTokenForUpdate(@Param("requestToken") String requestToken);

    @Select("SELECT * FROM flash_sale_order_request WHERE request_token = #{requestToken}")
    FlashSaleOrderRequestEntity selectByToken(@Param("requestToken") String requestToken);

    @Select("""
            SELECT request_token
            FROM flash_sale_order_request
            WHERE status = 'PROCESSING' AND next_attempt_at <= #{now}
              AND (recovery_claim_until IS NULL OR recovery_claim_until <= #{now})
            ORDER BY next_attempt_at, id
            LIMIT #{limit}
            """)
    List<String> selectRecoverableTokens(@Param("now") Instant now, @Param("limit") int limit);

    @Update("""
            UPDATE flash_sale_order_request
            SET recovery_claim_owner = #{owner}, recovery_claim_until = #{claimUntil}
            WHERE request_token = #{requestToken} AND status = 'PROCESSING'
              AND (recovery_claim_until IS NULL OR recovery_claim_until <= #{now})
            """)
    int claimRecovery(
            @Param("requestToken") String requestToken,
            @Param("owner") String owner,
            @Param("now") Instant now,
            @Param("claimUntil") Instant claimUntil);

    @Update("""
            UPDATE flash_sale_order_request
            SET recovery_claim_owner = NULL, recovery_claim_until = NULL
            WHERE request_token = #{requestToken} AND recovery_claim_owner = #{owner}
            """)
    int releaseRecoveryClaim(
            @Param("requestToken") String requestToken,
            @Param("owner") String owner);

    @Select("SELECT COUNT(*) FROM flash_sale_order_request WHERE status = 'PROCESSING'")
    long countProcessing();

    @Select("SELECT COUNT(*) FROM flash_sale_order_request WHERE status = #{status}")
    long countByStatus(@Param("status") String status);

    @Select("SELECT MIN(created_at) FROM flash_sale_order_request WHERE status = 'PROCESSING'")
    Instant selectOldestProcessingCreatedAt();
}
