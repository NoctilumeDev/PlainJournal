package com.ecommerce.catalog.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.catalog.infrastructure.persistence.entity.SearchRebuildRecoveryAuditEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SearchRebuildRecoveryAuditMapper extends BaseMapper<SearchRebuildRecoveryAuditEntity> {

    @Select("""
            SELECT * FROM catalog_search_rebuild_recovery_audit
            WHERE command_id = #{commandId}
            """)
    SearchRebuildRecoveryAuditEntity selectByCommandId(@Param("commandId") String commandId);

    @Select("""
            SELECT * FROM catalog_search_rebuild_recovery_audit
            WHERE command_id = #{commandId}
            FOR UPDATE
            """)
    SearchRebuildRecoveryAuditEntity selectByCommandIdForUpdate(
            @Param("commandId") String commandId);

    @Insert("""
            INSERT INTO catalog_search_rebuild_recovery_audit (
                id, command_id, rebuild_id, operator_id, reason, request_hash,
                status_before, status_after, created_at
            ) VALUES (
                #{id}, #{commandId}, #{rebuildId}, #{operatorId}, #{reason}, #{requestHash},
                #{statusBefore}, #{statusAfter}, #{createdAt}
            )
            ON DUPLICATE KEY UPDATE id = catalog_search_rebuild_recovery_audit.id
            """)
    int insertIdempotent(SearchRebuildRecoveryAuditEntity entity);
}
