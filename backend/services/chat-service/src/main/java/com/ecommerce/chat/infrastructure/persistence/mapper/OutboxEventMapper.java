package com.ecommerce.chat.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.chat.infrastructure.persistence.entity.OutboxEventEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

public interface OutboxEventMapper extends BaseMapper<OutboxEventEntity> {

    @Select("SELECT CURRENT_TIMESTAMP(3)")
    Instant currentTime();

    @Select("SELECT COUNT(*) FROM outbox_event WHERE status <> 'PUBLISHED'")
    long countUnpublished();

    @Select("SELECT MIN(created_at) FROM outbox_event WHERE status <> 'PUBLISHED'")
    Instant selectOldestUnpublishedCreatedAt();

    @Update("""
            UPDATE outbox_event
            SET status = 'PENDING', claimed_at = NULL, claim_owner = NULL,
                claim_until = NULL, updated_at = #{now}
            WHERE status = 'PUBLISHING'
              AND (claim_until IS NULL OR claim_until <= #{now})
            """)
    int resetStaleClaims(@Param("now") Instant now);

    @Select("""
            SELECT candidate.*
            FROM outbox_event candidate
            WHERE candidate.status = 'PENDING'
              AND candidate.next_attempt_at <= #{now}
              AND NOT EXISTS (
                  SELECT 1
                  FROM outbox_event predecessor
                  WHERE predecessor.aggregate_type = candidate.aggregate_type
                    AND predecessor.aggregate_id = candidate.aggregate_id
                    AND predecessor.status <> 'PUBLISHED'
                    AND (
                        predecessor.aggregate_version < candidate.aggregate_version
                        OR (
                            predecessor.aggregate_version = candidate.aggregate_version
                            AND predecessor.created_at < candidate.created_at
                        )
                        OR (
                            predecessor.aggregate_version = candidate.aggregate_version
                            AND predecessor.created_at = candidate.created_at
                            AND predecessor.id < candidate.id
                        )
                    )
              )
            ORDER BY candidate.created_at, candidate.id
            LIMIT #{limit}
            """)
    List<OutboxEventEntity> selectPublishable(
            @Param("now") Instant now,
            @Param("limit") int limit);

    @Update("""
            UPDATE outbox_event
            SET status = 'PUBLISHING', claimed_at = #{claimedAt},
                claim_owner = #{owner}, claim_until = #{claimUntil}, updated_at = #{claimedAt}
            WHERE id = #{id} AND status = 'PENDING'
              AND attempts = #{expectedAttempts}
              AND next_attempt_at <= #{claimedAt}
            """)
    int claim(
            @Param("id") String id,
            @Param("owner") String owner,
            @Param("expectedAttempts") int expectedAttempts,
            @Param("claimedAt") Instant claimedAt,
            @Param("claimUntil") Instant claimUntil);

    @Update("""
            UPDATE outbox_event
            SET status = 'PUBLISHED', published_at = #{now}, claimed_at = NULL,
                claim_owner = NULL, claim_until = NULL, last_error = NULL, updated_at = #{now}
            WHERE id = #{id} AND status = 'PUBLISHING' AND claim_owner = #{owner}
              AND claim_until > #{now}
            """)
    int markPublished(
            @Param("id") String id,
            @Param("owner") String owner,
            @Param("now") Instant now);

    @Update("""
            UPDATE outbox_event
            SET status = 'PENDING', attempts = attempts + 1, next_attempt_at = #{nextAttemptAt},
                claimed_at = NULL, claim_owner = NULL, claim_until = NULL,
                last_error = #{error}, updated_at = #{now}
            WHERE id = #{id} AND status = 'PUBLISHING' AND claim_owner = #{owner}
              AND claim_until > #{now}
            """)
    int markFailed(
            @Param("id") String id,
            @Param("owner") String owner,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("error") String error,
            @Param("now") Instant now);
}
