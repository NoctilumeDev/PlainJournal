package com.ecommerce.catalog.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.catalog.infrastructure.persistence.entity.SearchRecoveryAuditEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SearchRecoveryAuditMapper extends BaseMapper<SearchRecoveryAuditEntity> {

    @Select("""
            SELECT * FROM catalog_search_recovery_audit
            WHERE command_id = #{commandId}
            """)
    SearchRecoveryAuditEntity selectByCommandId(@Param("commandId") String commandId);

    @Select("""
            SELECT * FROM catalog_search_recovery_audit
            WHERE command_id = #{commandId}
            FOR UPDATE
            """)
    SearchRecoveryAuditEntity selectByCommandIdForUpdate(
            @Param("commandId") String commandId);

    @Insert("""
            INSERT INTO catalog_search_recovery_audit (
                id, command_id, outbox_id, operator_id, reason, request_hash,
                status_before, status_after, created_at
            ) VALUES (
                #{id}, #{commandId}, #{outboxId}, #{operatorId}, #{reason}, #{requestHash},
                #{statusBefore}, #{statusAfter}, #{createdAt}
            )
            ON DUPLICATE KEY UPDATE id = catalog_search_recovery_audit.id
            """)
    int insertIdempotent(SearchRecoveryAuditEntity entity);
}
