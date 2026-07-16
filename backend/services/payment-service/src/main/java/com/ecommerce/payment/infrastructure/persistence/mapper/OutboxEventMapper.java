package com.ecommerce.payment.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.payment.infrastructure.persistence.entity.OutboxEventEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

public interface OutboxEventMapper extends BaseMapper<OutboxEventEntity> {

    @Update("""
            UPDATE outbox_event
            SET status = 'PENDING', claimed_at = NULL, updated_at = #{now}
            WHERE status = 'PUBLISHING' AND claimed_at < #{staleBefore}
            """)
    int resetStaleClaims(@Param("staleBefore") Instant staleBefore, @Param("now") Instant now);

    @Select("""
            SELECT * FROM outbox_event
            WHERE status = 'PENDING' AND next_attempt_at <= #{now}
            ORDER BY created_at
            LIMIT #{limit}
            """)
    List<OutboxEventEntity> selectPublishable(@Param("now") Instant now, @Param("limit") int limit);

    @Update("""
            UPDATE outbox_event
            SET status = 'PUBLISHING', claimed_at = #{now}, updated_at = #{now}
            WHERE id = #{id} AND status = 'PENDING'
            """)
    int claim(@Param("id") String id, @Param("now") Instant now);

    @Update("""
            UPDATE outbox_event
            SET status = 'PUBLISHED', published_at = #{now}, claimed_at = NULL,
                last_error = NULL, updated_at = #{now}
            WHERE id = #{id} AND status = 'PUBLISHING'
            """)
    int markPublished(@Param("id") String id, @Param("now") Instant now);

    @Update("""
            UPDATE outbox_event
            SET status = 'PENDING', attempts = attempts + 1, next_attempt_at = #{nextAttemptAt},
                claimed_at = NULL, last_error = #{error}, updated_at = #{now}
            WHERE id = #{id} AND status = 'PUBLISHING'
            """)
    int markFailed(
            @Param("id") String id,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("error") String error,
            @Param("now") Instant now);
}
