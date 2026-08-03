package com.ecommerce.trade.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.trade.infrastructure.persistence.entity.CartMergeRequestEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;

public interface CartMergeRequestMapper extends BaseMapper<CartMergeRequestEntity> {

    @Insert("""
            INSERT INTO cart_user_lock (user_id, created_at, updated_at)
            VALUES (#{userId}, #{now}, #{now})
            ON DUPLICATE KEY UPDATE user_id = user_id
            """)
    int ensureUserLock(@Param("userId") Long userId, @Param("now") Instant now);

    @Select("SELECT user_id FROM cart_user_lock WHERE user_id = #{userId} FOR UPDATE")
    Long lockUser(@Param("userId") Long userId);

    @Insert("""
            INSERT INTO cart_merge_request (id, user_id, merge_key, request_hash, created_at)
            VALUES (#{entity.id}, #{entity.userId}, #{entity.mergeKey}, #{entity.requestHash}, #{entity.createdAt})
            ON DUPLICATE KEY UPDATE id = id
            """)
    int insertOrKeepExisting(@Param("entity") CartMergeRequestEntity entity);

    @Select("""
            SELECT * FROM cart_merge_request
            WHERE user_id = #{userId} AND merge_key = #{mergeKey}
            FOR UPDATE
            """)
    CartMergeRequestEntity selectByKeyForUpdate(
            @Param("userId") Long userId,
            @Param("mergeKey") String mergeKey);
}
