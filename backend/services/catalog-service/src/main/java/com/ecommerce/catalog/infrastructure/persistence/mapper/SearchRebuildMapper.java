package com.ecommerce.catalog.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.catalog.infrastructure.persistence.entity.SearchRebuildEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

public interface SearchRebuildMapper extends BaseMapper<SearchRebuildEntity> {

    @Select("SELECT CURRENT_TIMESTAMP(3)")
    Instant currentTime();

    @Select("""
            SELECT * FROM catalog_search_rebuild
            WHERE command_id = #{commandId}
            """)
    SearchRebuildEntity selectByCommandId(@Param("commandId") String commandId);

    @Select("""
            SELECT * FROM catalog_search_rebuild
            WHERE command_id = #{commandId}
            FOR UPDATE
            """)
    SearchRebuildEntity selectByCommandIdForUpdate(
            @Param("commandId") String commandId);

    @Select("""
            SELECT * FROM catalog_search_rebuild
            WHERE id = #{id}
            FOR UPDATE
            """)
    SearchRebuildEntity selectByIdForUpdate(@Param("id") Long id);

    @Insert("""
            INSERT INTO catalog_search_rebuild (
                id, command_id, operator_id, reason, request_hash, status,
                target_index, attempts, indexed_count, claimed_at, claim_owner,
                claim_until, started_at, completed_at, last_error, created_at, updated_at
            ) VALUES (
                #{id}, #{commandId}, #{operatorId}, #{reason}, #{requestHash}, #{status},
                #{targetIndex}, #{attempts}, #{indexedCount}, #{claimedAt}, #{claimOwner},
                #{claimUntil}, #{startedAt}, #{completedAt}, #{lastError}, #{createdAt}, #{updatedAt}
            )
            ON DUPLICATE KEY UPDATE id = catalog_search_rebuild.id
            """)
    int insertIdempotent(SearchRebuildEntity entity);

    @Select("""
            SELECT * FROM catalog_search_rebuild
            WHERE status = 'PENDING'
            ORDER BY created_at, id
            LIMIT #{limit}
            """)
    List<SearchRebuildEntity> selectPending(@Param("limit") int limit);

    @Update("""
            UPDATE catalog_search_rebuild
            SET status = 'PENDING', claimed_at = NULL, claim_owner = NULL,
                claim_until = NULL, updated_at = #{now}
            WHERE status = 'RUNNING'
              AND (claim_until IS NULL OR claim_until <= #{now})
            """)
    int resetStaleClaims(@Param("now") Instant now);

    @Update("""
            UPDATE catalog_search_rebuild
            SET status = 'RUNNING', claimed_at = #{now}, claim_owner = #{owner},
                claim_until = #{claimUntil}, started_at = COALESCE(started_at, #{now}),
                target_index = #{targetIndex}, updated_at = #{now}
            WHERE id = #{id} AND status = 'PENDING'
              AND attempts = #{expectedAttempts}
            """)
    int claim(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("expectedAttempts") int expectedAttempts,
            @Param("targetIndex") String targetIndex,
            @Param("now") Instant now,
            @Param("claimUntil") Instant claimUntil);

    @Update("""
            UPDATE catalog_search_rebuild
            SET claim_until = #{claimUntil}, indexed_count = #{indexedCount}, updated_at = #{now}
            WHERE id = #{id} AND status = 'RUNNING' AND claim_owner = #{owner}
              AND claim_until > #{now}
            """)
    int renew(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("indexedCount") long indexedCount,
            @Param("now") Instant now,
            @Param("claimUntil") Instant claimUntil);

    @Update("""
            UPDATE catalog_search_rebuild
            SET status = 'SUCCEEDED', indexed_count = #{indexedCount}, completed_at = #{now},
                claimed_at = NULL, claim_owner = NULL, claim_until = NULL,
                last_error = NULL, updated_at = #{now}
            WHERE id = #{id} AND status = 'RUNNING' AND claim_owner = #{owner}
              AND target_index = #{targetIndex}
              AND claim_until > #{now}
            """)
    int markSucceeded(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("targetIndex") String targetIndex,
            @Param("indexedCount") long indexedCount,
            @Param("now") Instant now);

    @Update("""
            UPDATE catalog_search_rebuild
            SET status = CASE
                    WHEN attempts + 1 >= #{maxAttempts} THEN 'NEEDS_ATTENTION'
                    ELSE 'PENDING'
                END,
                attempts = attempts + 1,
                claimed_at = NULL,
                claim_owner = NULL,
                claim_until = NULL,
                last_error = #{error},
                updated_at = #{now}
            WHERE id = #{id} AND status = 'RUNNING' AND claim_owner = #{owner}
              AND claim_until > #{now}
            """)
    int markFailed(
            @Param("id") Long id,
            @Param("owner") String owner,
            @Param("maxAttempts") int maxAttempts,
            @Param("error") String error,
            @Param("now") Instant now);

    @Update("""
            UPDATE catalog_search_rebuild
            SET status = 'PENDING', attempts = 0, indexed_count = 0,
                claimed_at = NULL, claim_owner = NULL, claim_until = NULL,
                started_at = NULL, completed_at = NULL, last_error = NULL, updated_at = #{now}
            WHERE id = #{id} AND status = 'NEEDS_ATTENTION'
            """)
    int recover(@Param("id") Long id, @Param("now") Instant now);

    @Select("""
            SELECT * FROM catalog_search_rebuild
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<SearchRebuildEntity> selectRecent(@Param("limit") int limit);
}
