package com.ecommerce.payment.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.payment.infrastructure.persistence.entity.RefundDispatchRetryAuditEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface RefundDispatchRetryAuditMapper extends BaseMapper<RefundDispatchRetryAuditEntity> {

    @Insert("""
            INSERT INTO refund_dispatch_retry_audit
                (id, command_id, request_hash, refund_no, operator_id, reason, outcome, error_code,
                 before_refund_status, before_request_status, before_request_attempts,
                 before_last_error,
                 after_refund_status, after_request_status, after_request_attempts, created_at)
            VALUES
                (#{entity.id}, #{entity.commandId}, #{entity.requestHash}, #{entity.refundNo},
                 #{entity.operatorId}, #{entity.reason}, #{entity.outcome}, #{entity.errorCode},
                 #{entity.beforeRefundStatus}, #{entity.beforeRequestStatus},
                 #{entity.beforeRequestAttempts}, #{entity.beforeLastError}, #{entity.afterRefundStatus},
                 #{entity.afterRequestStatus}, #{entity.afterRequestAttempts}, #{entity.createdAt})
            ON DUPLICATE KEY UPDATE id = id
            """)
    int insertOrLockExisting(@Param("entity") RefundDispatchRetryAuditEntity entity);

    @Select("SELECT * FROM refund_dispatch_retry_audit WHERE command_id = #{commandId} FOR UPDATE")
    RefundDispatchRetryAuditEntity selectByCommandIdForUpdate(@Param("commandId") String commandId);

    @Select("""
            SELECT * FROM refund_dispatch_retry_audit
            WHERE refund_no = #{refundNo}
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<RefundDispatchRetryAuditEntity> selectByRefundNo(
            @Param("refundNo") String refundNo,
            @Param("limit") int limit);
}
