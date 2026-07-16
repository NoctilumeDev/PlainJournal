package com.ecommerce.inventory.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.inventory.infrastructure.persistence.entity.OutboxEventEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

public interface OutboxEventMapper extends BaseMapper<OutboxEventEntity> {

    @Update("""
            UPDATE outbox_event
            SET status = 'PENDING', updated_at = #{now}, next_attempt_at = #{now}
            WHERE status = 'PROCESSING' AND updated_at < #{staleBefore}
            """)
    int resetStaleClaims(@Param("staleBefore") Instant staleBefore, @Param("now") Instant now);

    @Select("""
            SELECT * FROM outbox_event
            WHERE status = 'PENDING' AND next_attempt_at <= #{now}
            ORDER BY id
            LIMIT #{limit}
            """)
    List<OutboxEventEntity> selectPublishable(@Param("now") Instant now, @Param("limit") int limit);

    @Update("""
            UPDATE outbox_event
            SET status = 'PROCESSING', updated_at = #{now}
            WHERE id = #{id} AND status = 'PENDING' AND next_attempt_at <= #{now}
            """)
    int claim(@Param("id") Long id, @Param("now") Instant now);

    @Update("""
            UPDATE outbox_event
            SET status = 'PUBLISHED', published_at = #{now}, updated_at = #{now}, last_error = NULL
            WHERE id = #{id} AND status = 'PROCESSING'
            """)
    int markPublished(@Param("id") Long id, @Param("now") Instant now);

    @Update("""
            UPDATE outbox_event
            SET status = 'PENDING', attempts = attempts + 1, next_attempt_at = #{nextAttemptAt},
                last_error = #{lastError}, updated_at = #{now}
            WHERE id = #{id} AND status = 'PROCESSING'
            """)
    int markFailed(
            @Param("id") Long id,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("lastError") String lastError,
            @Param("now") Instant now);
}
