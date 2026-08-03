package com.ecommerce.marketing.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.marketing.infrastructure.persistence.entity.FlashSaleOutboxEventEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

public interface FlashSaleOutboxEventMapper extends BaseMapper<FlashSaleOutboxEventEntity> {

    @Select("SELECT CURRENT_TIMESTAMP(3)")
    Instant currentTime();

    @Select("""
            SELECT *
            FROM flash_sale_outbox_event
            WHERE (status = 'PENDING' AND next_attempt_at <= #{now})
               OR (status = 'PUBLISHING' AND claim_until <= #{now})
            ORDER BY created_at, id
            LIMIT #{limit}
            """)
    List<FlashSaleOutboxEventEntity> selectClaimCandidates(
            @Param("now") Instant now,
            @Param("limit") int limit);

    @Update("""
            UPDATE flash_sale_outbox_event
            SET status = 'PUBLISHING',
                claim_owner = #{claimOwner},
                claim_until = #{claimUntil},
                updated_at = #{now}
            WHERE id = #{eventId}
              AND attempts = #{expectedAttempts}
              AND (
                  (status = 'PENDING' AND next_attempt_at <= #{now})
                  OR (status = 'PUBLISHING' AND claim_until <= #{now})
              )
            """)
    int claim(
            @Param("eventId") String eventId,
            @Param("claimOwner") String claimOwner,
            @Param("expectedAttempts") int expectedAttempts,
            @Param("now") Instant now,
            @Param("claimUntil") Instant claimUntil);

    @Select("""
            SELECT *
            FROM flash_sale_outbox_event
            WHERE id = #{eventId}
              AND status = 'PUBLISHING'
              AND claim_owner = #{claimOwner}
              AND claim_until > #{now}
            """)
    FlashSaleOutboxEventEntity selectClaimed(
            @Param("eventId") String eventId,
            @Param("claimOwner") String claimOwner,
            @Param("now") Instant now);

    @Update("""
            UPDATE flash_sale_outbox_event
            SET status = 'PUBLISHED',
                published_at = #{now},
                claim_owner = NULL,
                claim_until = NULL,
                last_error = NULL,
                updated_at = #{now}
            WHERE id = #{eventId}
              AND status = 'PUBLISHING'
              AND claim_owner = #{claimOwner}
              AND claim_until > #{now}
            """)
    int markPublished(
            @Param("eventId") String eventId,
            @Param("claimOwner") String claimOwner,
            @Param("now") Instant now);

    @Update("""
            UPDATE flash_sale_outbox_event
            SET status = 'PENDING',
                attempts = attempts + 1,
                next_attempt_at = #{nextAttemptAt},
                claim_owner = NULL,
                claim_until = NULL,
                last_error = #{lastError},
                updated_at = #{now}
            WHERE id = #{eventId}
              AND status = 'PUBLISHING'
              AND claim_owner = #{claimOwner}
              AND claim_until > #{now}
            """)
    int markFailed(
            @Param("eventId") String eventId,
            @Param("claimOwner") String claimOwner,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("lastError") String lastError,
            @Param("now") Instant now);

    @Select("SELECT COUNT(*) FROM flash_sale_outbox_event WHERE status <> 'PUBLISHED'")
    long countUnpublished();

    @Select("SELECT MIN(created_at) FROM flash_sale_outbox_event WHERE status <> 'PUBLISHED'")
    Instant selectOldestUnpublishedCreatedAt();
}
