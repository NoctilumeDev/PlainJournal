package com.ecommerce.catalog.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.catalog.infrastructure.persistence.entity.SearchReconciliationEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

public interface SearchReconciliationMapper extends BaseMapper<SearchReconciliationEntity> {

    @Select("SELECT CURRENT_TIMESTAMP(3)")
    Instant currentTime();

    @Insert("""
            INSERT INTO catalog_search_reconciliation
                (id, product_id, issue_type, status, mysql_revision, index_revision,
                 occurrences, first_detected_at, last_detected_at, resolved_at)
            VALUES
                (#{id}, #{productId}, #{issueType}, 'OPEN', #{mysqlRevision}, #{indexRevision},
                 1, #{now}, #{now}, NULL)
            ON DUPLICATE KEY UPDATE id = id
            """)
    int insertIfAbsent(
            @Param("id") Long id,
            @Param("productId") Long productId,
            @Param("issueType") String issueType,
            @Param("mysqlRevision") Long mysqlRevision,
            @Param("indexRevision") Long indexRevision,
            @Param("now") Instant now);

    @Update("""
            UPDATE catalog_search_reconciliation
            SET status = 'OPEN', mysql_revision = #{mysqlRevision},
                index_revision = #{indexRevision}, occurrences = occurrences + 1,
                last_detected_at = #{now}, resolved_at = NULL
            WHERE product_id = #{productId} AND issue_type = #{issueType}
            """)
    int touchOpen(
            @Param("productId") Long productId,
            @Param("issueType") String issueType,
            @Param("mysqlRevision") Long mysqlRevision,
            @Param("indexRevision") Long indexRevision,
            @Param("now") Instant now);

    @Update("""
            UPDATE catalog_search_reconciliation
            SET status = 'RESOLVED', resolved_at = #{now}, last_detected_at = #{now}
            WHERE id = #{id} AND status = 'OPEN'
              AND occurrences = #{expectedOccurrences}
              AND last_detected_at = #{expectedLastDetectedAt}
            """)
    int markResolved(
            @Param("id") Long id,
            @Param("expectedOccurrences") int expectedOccurrences,
            @Param("expectedLastDetectedAt") Instant expectedLastDetectedAt,
            @Param("now") Instant now);

    @Select("""
            SELECT * FROM catalog_search_reconciliation
            WHERE status = #{status}
            ORDER BY last_detected_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<SearchReconciliationEntity> selectByStatus(
            @Param("status") String status,
            @Param("limit") int limit);

    @Select("""
            SELECT * FROM catalog_search_reconciliation
            WHERE status = 'OPEN'
            ORDER BY product_id, issue_type
            LIMIT #{limit}
            """)
    List<SearchReconciliationEntity> selectOpen(@Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM catalog_search_reconciliation WHERE status = 'OPEN'")
    long countOpen();
}
