package com.ecommerce.catalog.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.catalog.infrastructure.persistence.entity.ProductSpuEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

public interface ProductSpuMapper extends BaseMapper<ProductSpuEntity> {

    @Select("SELECT CURRENT_TIMESTAMP(3)")
    Instant currentTime();

    @Update("""
            UPDATE product_spu
            SET search_revision = search_revision + 1
            WHERE id = #{productId}
            """)
    int incrementSearchRevision(@Param("productId") Long productId);

    @Select("""
            SELECT * FROM product_spu
            WHERE status = 'ACTIVE'
              AND id > #{afterId}
            ORDER BY id
            LIMIT #{limit}
            """)
    List<ProductSpuEntity> selectActiveSearchBatch(
            @Param("afterId") long afterId,
            @Param("limit") int limit);

    @Select("""
            <script>
            SELECT * FROM product_spu
            WHERE status = 'ACTIVE'
            <if test="categoryId != null">
              AND category_id = #{categoryId}
            </if>
            <if test="keyword != null and keyword != ''">
              AND title LIKE CONCAT('%', #{keyword}, '%')
            </if>
            <if test="cursorCreatedAt != null">
              AND (
                created_at &lt; #{cursorCreatedAt}
                OR (created_at = #{cursorCreatedAt} AND id &lt; #{cursorId})
              )
            </if>
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit}
            </script>
            """)
    List<ProductSpuEntity> selectPublicCursorPage(
            @Param("categoryId") Long categoryId,
            @Param("keyword") String keyword,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorId") Long cursorId,
            @Param("limit") int limit);
}
