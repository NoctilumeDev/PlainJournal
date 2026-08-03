package com.ecommerce.inventory.infrastructure.persistence.mapper;

import com.ecommerce.platform.common.observability.ConsumerFailureEntry;
import com.ecommerce.platform.common.observability.ConsumerFailureRetryEntry;
import com.ecommerce.platform.common.observability.ConsumerFailureRetryStore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

public interface ConsumerFailureMapper extends ConsumerFailureRetryStore {
    @Override
    @Select("SELECT CURRENT_TIMESTAMP(3)")
    Instant currentTime();

    @Insert("""
            INSERT IGNORE INTO consumer_failure
                (message_id, consumer_group, raw_payload, attempts, status, last_error,
                 first_failed_at, last_failed_at, next_attempt_at)
            VALUES
                (#{messageId}, #{consumerGroup}, #{payload}, #{attempts}, #{status}, #{error},
                 #{now}, #{now}, #{nextAttemptAt})
            """)
    int insertIfAbsent(@Param("messageId") String messageId, @Param("consumerGroup") String consumerGroup,
            @Param("payload") String payload, @Param("attempts") int attempts,
            @Param("status") String status, @Param("error") String error,
            @Param("now") Instant now, @Param("nextAttemptAt") Instant nextAttemptAt);

    @Update("""
            UPDATE consumer_failure
            SET attempts = GREATEST(attempts, #{attempts}),
                status = #{status},
                last_error = #{error},
                last_failed_at = #{now},
                recovered_at = NULL,
                next_attempt_at = CASE
                    WHEN #{status} = 'NEEDS_ATTENTION' THEN NULL
                    WHEN next_attempt_at IS NULL OR next_attempt_at > #{nextAttemptAt}
                        THEN #{nextAttemptAt}
                    ELSE next_attempt_at
                END,
                claimed_at = NULL,
                claim_owner = NULL,
                claim_until = NULL
            WHERE message_id = #{messageId}
              AND consumer_group = #{consumerGroup}
              AND status = 'RETRYING'
              AND (claim_until IS NULL OR claim_until <= #{now})
            """)
    int markFailed(@Param("messageId") String messageId, @Param("consumerGroup") String consumerGroup,
            @Param("attempts") int attempts, @Param("status") String status,
            @Param("error") String error, @Param("now") Instant now,
            @Param("nextAttemptAt") Instant nextAttemptAt);

    @Update("""
            UPDATE consumer_failure
            SET status = 'RECOVERED',
                recovered_at = #{now},
                next_attempt_at = NULL,
                claimed_at = NULL,
                claim_owner = NULL,
                claim_until = NULL
            WHERE message_id = #{messageId} AND consumer_group = #{consumerGroup}
              AND status <> 'RECOVERED'
            """)
    int markRecovered(@Param("messageId") String messageId,
            @Param("consumerGroup") String consumerGroup, @Param("now") Instant now);

    @Override
    @Select("""
            SELECT message_id, consumer_group, raw_payload, attempts
            FROM consumer_failure
            WHERE status = 'RETRYING'
              AND next_attempt_at <= #{now}
              AND (claim_until IS NULL OR claim_until <= #{now})
            ORDER BY next_attempt_at, message_id
            LIMIT #{limit}
            """)
    List<ConsumerFailureRetryEntry> selectRetryable(
            @Param("now") Instant now,
            @Param("limit") int limit);

    @Override
    @Update("""
            UPDATE consumer_failure
            SET claimed_at = #{now},
                claim_owner = #{owner},
                claim_until = #{claimUntil}
            WHERE message_id = #{messageId}
              AND consumer_group = #{consumerGroup}
              AND status = 'RETRYING'
              AND attempts = #{expectedAttempts}
              AND next_attempt_at <= #{now}
              AND (claim_until IS NULL OR claim_until <= #{now})
            """)
    int claimRetry(
            @Param("messageId") String messageId,
            @Param("consumerGroup") String consumerGroup,
            @Param("owner") String owner,
            @Param("expectedAttempts") int expectedAttempts,
            @Param("now") Instant now,
            @Param("claimUntil") Instant claimUntil);

    @Override
    @Update("""
            UPDATE consumer_failure
            SET status = 'RECOVERED',
                recovered_at = #{now},
                next_attempt_at = NULL,
                claimed_at = NULL,
                claim_owner = NULL,
                claim_until = NULL
            WHERE message_id = #{messageId}
              AND consumer_group = #{consumerGroup}
              AND status = 'RETRYING'
              AND claim_owner = #{owner}
              AND claim_until > #{now}
            """)
    int markRetryRecovered(
            @Param("messageId") String messageId,
            @Param("consumerGroup") String consumerGroup,
            @Param("owner") String owner,
            @Param("now") Instant now);

    @Override
    @Update("""
            UPDATE consumer_failure
            SET attempts = GREATEST(attempts, #{attempts}),
                status = #{status},
                last_error = #{error},
                last_failed_at = #{now},
                recovered_at = NULL,
                next_attempt_at = #{nextAttemptAt},
                claimed_at = NULL,
                claim_owner = NULL,
                claim_until = NULL
            WHERE message_id = #{messageId}
              AND consumer_group = #{consumerGroup}
              AND status = 'RETRYING'
              AND claim_owner = #{owner}
              AND claim_until > #{now}
            """)
    int markRetryFailed(
            @Param("messageId") String messageId,
            @Param("consumerGroup") String consumerGroup,
            @Param("owner") String owner,
            @Param("attempts") int attempts,
            @Param("status") String status,
            @Param("error") String error,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("now") Instant now);

    @Override
    @Select("SELECT COUNT(*) FROM consumer_failure WHERE status = #{status}")
    long countByStatus(@Param("status") String status);

    @Override
    @Select("""
            SELECT MIN(first_failed_at)
            FROM consumer_failure
            WHERE status IN ('RETRYING', 'NEEDS_ATTENTION')
            """)
    Instant selectOldestActiveFailedAt();

    @Override
    @Select("""
            SELECT message_id, consumer_group, attempts, status, last_error,
                   first_failed_at, last_failed_at
            FROM consumer_failure
            WHERE status IN ('RETRYING', 'NEEDS_ATTENTION')
            ORDER BY last_failed_at DESC, message_id ASC
            LIMIT #{limit}
            """)
    List<ConsumerFailureEntry> selectRecentActive(@Param("limit") int limit);
}
