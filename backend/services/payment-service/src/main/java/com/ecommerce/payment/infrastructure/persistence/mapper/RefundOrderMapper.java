package com.ecommerce.payment.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.platform.common.observability.BusinessProcessEntry;
import com.ecommerce.payment.infrastructure.persistence.entity.RefundOrderEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

public interface RefundOrderMapper extends BaseMapper<RefundOrderEntity> {

    @Select("SELECT CURRENT_TIMESTAMP(3)")
    Instant currentTime();

    @Insert("""
            INSERT INTO refund_order
                (id, refund_no, after_sale_no, order_no, payment_id, payment_no, user_id,
                 request_hash, channel, status, amount, request_status, request_attempts,
                 next_request_at, version, created_at, updated_at)
            VALUES
                (#{entity.id}, #{entity.refundNo}, #{entity.afterSaleNo}, #{entity.orderNo},
                 #{entity.paymentId}, #{entity.paymentNo}, #{entity.userId}, #{entity.requestHash},
                 #{entity.channel}, #{entity.status}, #{entity.amount}, #{entity.requestStatus},
                 #{entity.requestAttempts}, #{entity.nextRequestAt}, #{entity.version},
                 #{entity.createdAt}, #{entity.updatedAt})
            ON DUPLICATE KEY UPDATE id = id
            """)
    int insertOrLockExisting(@Param("entity") RefundOrderEntity entity);

    @Select("SELECT * FROM refund_order WHERE after_sale_no = #{afterSaleNo} FOR UPDATE")
    RefundOrderEntity selectByAfterSaleNoForUpdate(@Param("afterSaleNo") String afterSaleNo);

    @Select("SELECT * FROM refund_order WHERE refund_no = #{refundNo} FOR UPDATE")
    RefundOrderEntity selectByRefundNoForUpdate(@Param("refundNo") String refundNo);

    @Select("SELECT * FROM refund_order WHERE payment_id = #{paymentId} FOR UPDATE")
    RefundOrderEntity selectByPaymentIdForUpdate(@Param("paymentId") Long paymentId);

    @Select("""
            SELECT * FROM refund_order
            WHERE status = 'PROCESSING'
              AND request_status = 'PENDING'
              AND next_request_at <= #{now}
            ORDER BY next_request_at, created_at
            LIMIT #{limit}
            """)
    List<RefundOrderEntity> selectDueRequests(@Param("now") Instant now, @Param("limit") int limit);

    @Update("""
            UPDATE refund_order
            SET request_status = 'REQUESTING', request_claimed_at = #{claimedAt},
                request_claim_owner = #{owner}, request_claim_until = #{claimUntil},
                updated_at = #{claimedAt}
            WHERE id = #{id} AND status = 'PROCESSING' AND request_status = 'PENDING'
              AND request_attempts = #{expectedAttempts}
              AND next_request_at <= #{dueAt}
            """)
    int claimRequest(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("expectedAttempts") int expectedAttempts,
            @Param("dueAt") Instant dueAt,
            @Param("claimedAt") Instant claimedAt,
            @Param("claimUntil") Instant claimUntil);

    @Update("""
            UPDATE refund_order
            SET request_status = 'PENDING', request_claimed_at = NULL,
                request_claim_owner = NULL, request_claim_until = NULL,
                next_request_at = #{now}, updated_at = #{now}
            WHERE status = 'PROCESSING' AND request_status = 'REQUESTING'
              AND (request_claim_until IS NULL OR request_claim_until <= #{expiredAt})
            """)
    int resetStaleRequestClaims(@Param("expiredAt") Instant expiredAt, @Param("now") Instant now);

    @Update("""
            UPDATE refund_order
            SET request_status = 'SENT', request_attempts = request_attempts + 1,
                request_claimed_at = NULL, request_claim_owner = NULL, request_claim_until = NULL,
                next_request_at = NULL,
                request_sent_at = #{now}, last_request_error = NULL, updated_at = #{now}
            WHERE id = #{id} AND request_status = 'REQUESTING'
              AND request_claim_owner = #{owner} AND request_claim_until > #{now}
            """)
    int markRequestSent(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("now") Instant now);

    @Update("""
            UPDATE refund_order
            SET request_status = CASE
                    WHEN request_attempts + 1 >= #{maxAttempts} THEN 'NEEDS_ATTENTION'
                    ELSE 'PENDING'
                END,
                request_claimed_at = NULL, request_claim_owner = NULL, request_claim_until = NULL,
                next_request_at = CASE
                    WHEN request_attempts + 1 >= #{maxAttempts} THEN NULL
                    ELSE #{nextRequestAt}
                END,
                request_attempts = request_attempts + 1,
                last_request_error = #{error}, updated_at = #{now}
            WHERE id = #{id} AND request_status = 'REQUESTING'
              AND request_claim_owner = #{owner} AND request_claim_until > #{now}
            """)
    int markRequestFailed(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("maxAttempts") int maxAttempts,
            @Param("nextRequestAt") Instant nextRequestAt,
            @Param("error") String error,
            @Param("now") Instant now);

    @Update("""
            UPDATE refund_order SET request_status = 'SENT', request_claimed_at = NULL,
                request_claim_owner = NULL, request_claim_until = NULL, next_request_at = NULL,
                request_sent_at = COALESCE(request_sent_at, #{now}), last_request_error = NULL
            WHERE id = #{id}
            """)
    int markRequestAcknowledged(@Param("id") Long id, @Param("now") Instant now);

    @Update("""
            UPDATE refund_order
            SET status = 'PROCESSING', request_status = 'PENDING',
                request_attempts = 0, next_request_at = #{now},
                request_claimed_at = NULL, request_claim_owner = NULL, request_claim_until = NULL,
                request_sent_at = NULL, last_request_error = NULL,
                updated_at = #{now}
            WHERE id = #{id}
              AND ((status = 'PROCESSING' AND request_status = 'NEEDS_ATTENTION')
                OR (status = 'FAILED' AND request_status = 'SENT'))
            """)
    int resetRequestForManualRetry(@Param("id") Long id, @Param("now") Instant now);

    @Select("SELECT COUNT(*) FROM refund_order WHERE status = #{status}")
    long countByStatus(@Param("status") String status);

    @Select("SELECT MIN(updated_at) FROM refund_order WHERE status = #{status}")
    Instant selectOldestUpdatedAtByStatus(@Param("status") String status);

    @Select("SELECT COUNT(*) FROM refund_order WHERE request_status = #{requestStatus}")
    long countByRequestStatus(@Param("requestStatus") String requestStatus);

    @Select("SELECT MIN(updated_at) FROM refund_order WHERE request_status = #{requestStatus}")
    Instant selectOldestUpdatedAtByRequestStatus(@Param("requestStatus") String requestStatus);

    @Select("""
            SELECT CASE WHEN request_status = 'NEEDS_ATTENTION'
                        THEN 'REFUND_DISPATCH' ELSE 'REFUND' END AS domain,
                   CASE WHEN request_status = 'NEEDS_ATTENTION'
                        THEN request_status ELSE status END AS status,
                   refund_no AS reference_no, request_status AS stage,
                   last_request_error AS last_error, updated_at AS last_updated_at
            FROM refund_order
            WHERE status IN ('PROCESSING', 'FAILED')
               OR request_status = 'NEEDS_ATTENTION'
            ORDER BY updated_at, refund_no
            LIMIT #{limit}
            """)
    List<BusinessProcessEntry> selectOldestActive(@Param("limit") int limit);
}
