package com.ecommerce.chat.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.chat.infrastructure.persistence.entity.ChatAttachmentScanRetryAuditEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ChatAttachmentScanRetryAuditMapper
        extends BaseMapper<ChatAttachmentScanRetryAuditEntity> {

    @Insert("""
            INSERT INTO chat_attachment_scan_retry_audit (
                id, command_id, request_hash, upload_id, operator_id, reason,
                before_status, before_attempts, before_last_error, outcome,
                error_code, after_status, after_attempts, created_at
            ) VALUES (
                #{id}, #{commandId}, #{requestHash}, #{uploadId}, #{operatorId}, #{reason},
                #{beforeStatus}, #{beforeAttempts}, #{beforeLastError}, #{outcome},
                #{errorCode}, #{afterStatus}, #{afterAttempts}, #{createdAt}
            )
            ON DUPLICATE KEY UPDATE id = chat_attachment_scan_retry_audit.id
            """)
    int insertIdempotent(ChatAttachmentScanRetryAuditEntity entity);

    @Select("""
            SELECT *
            FROM chat_attachment_scan_retry_audit
            WHERE command_id = #{commandId}
            FOR UPDATE
            """)
    ChatAttachmentScanRetryAuditEntity selectByCommandIdForUpdate(
            @Param("commandId") String commandId);

    @Select("""
            SELECT *
            FROM chat_attachment_scan_retry_audit
            WHERE upload_id = #{uploadId}
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<ChatAttachmentScanRetryAuditEntity> selectByUploadId(
            @Param("uploadId") Long uploadId,
            @Param("limit") int limit);
}
