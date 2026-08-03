package com.ecommerce.catalog.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.catalog.infrastructure.persistence.entity.SearchOutboxEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

public interface SearchOutboxMapper extends BaseMapper<SearchOutboxEntity> {

    @Select("SELECT CURRENT_TIMESTAMP(3)")
    Instant currentTime();

    @Select("""
            SELECT * FROM catalog_search_outbox
            WHERE status = 'PENDING' AND next_attempt_at <= #{now}
            ORDER BY created_at, id
            LIMIT #{limit}
            """)
    List<SearchOutboxEntity> selectDispatchable(
            @Param("now") Instant now,
            @Param("limit") int limit);

    @Update("""
            UPDATE catalog_search_outbox
            SET status = 'PENDING', claimed_at = NULL, claim_owner = NULL,
                claim_until = NULL, updated_at = #{now}
            WHERE status = 'PROJECTING'
              AND (claim_until IS NULL OR claim_until <= #{now})
            """)
    int resetStaleClaims(@Param("now") Instant now);

    @Update("""
            UPDATE catalog_search_outbox
            SET status = 'PROJECTING', claimed_at = #{now}, claim_owner = #{owner},
                claim_until = #{claimUntil}, updated_at = #{now}
            WHERE id = #{id} AND status = 'PENDING'
              AND attempts = #{expectedAttempts}
              AND next_attempt_at <= #{now}
            """)
    int claim(
            @Param("id") String id,
            @Param("owner") String owner,
            @Param("expectedAttempts") int expectedAttempts,
            @Param("now") Instant now,
            @Param("claimUntil") Instant claimUntil);

    @Update("""
            UPDATE catalog_search_outbox
            SET status = 'PUBLISHED', published_at = #{now}, claimed_at = NULL,
                claim_owner = NULL, claim_until = NULL, last_error = NULL, updated_at = #{now}
            WHERE id = #{id} AND status = 'PROJECTING'
              AND claim_owner = #{owner} AND claim_until > #{now}
            """)
    int markPublished(
            @Param("id") String id,
            @Param("owner") String owner,
            @Param("now") Instant now);

    @Update("""
            UPDATE catalog_search_outbox
            SET status = CASE
                    WHEN attempts + 1 >= #{maxAttempts} THEN 'NEEDS_ATTENTION'
                    ELSE 'PENDING'
                END,
                attempts = attempts + 1,
                next_attempt_at = #{nextAttemptAt},
                claimed_at = NULL,
                claim_owner = NULL,
                claim_until = NULL,
                last_error = #{error},
                updated_at = #{now}
            WHERE id = #{id} AND status = 'PROJECTING'
              AND claim_owner = #{owner} AND claim_until > #{now}
            """)
    int markFailed(
            @Param("id") String id,
            @Param("owner") String owner,
            @Param("maxAttempts") int maxAttempts,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("error") String error,
            @Param("now") Instant now);

    @Update("""
            UPDATE catalog_search_outbox
            SET status = 'PENDING', attempts = 0, next_attempt_at = #{now},
                claimed_at = NULL, claim_owner = NULL, claim_until = NULL,
                last_error = NULL, updated_at = #{now}
            WHERE id = #{id} AND status = 'NEEDS_ATTENTION'
            """)
    int recover(@Param("id") String id, @Param("now") Instant now);

    @Select("""
            SELECT * FROM catalog_search_outbox
            WHERE status = #{status}
            ORDER BY updated_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<SearchOutboxEntity> selectByStatus(
            @Param("status") String status,
            @Param("limit") int limit);

    @Select("""
            SELECT * FROM catalog_search_outbox
            WHERE id = #{id}
            FOR UPDATE
            """)
    SearchOutboxEntity selectByIdForUpdate(@Param("id") String id);

    @Select("SELECT COUNT(*) FROM catalog_search_outbox WHERE status <> 'PUBLISHED'")
    long countUnpublished();

    @Select("""
            SELECT COUNT(*) FROM catalog_search_outbox
            WHERE status = 'NEEDS_ATTENTION'
            """)
    long countNeedsAttention();

    @Select("""
            SELECT MIN(created_at) FROM catalog_search_outbox
            WHERE status <> 'PUBLISHED'
            """)
    Instant selectOldestUnpublishedCreatedAt();
}
