package com.ecommerce.chat.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@TableName("chat_attachment_scan_retry_audit")
public class ChatAttachmentScanRetryAuditEntity {
    @TableId
    private Long id;
    private String commandId;
    private String requestHash;
    private Long uploadId;
    private Long operatorId;
    private String reason;
    private String beforeStatus;
    private Integer beforeAttempts;
    private String beforeLastError;
    private String outcome;
    private String errorCode;
    private String afterStatus;
    private Integer afterAttempts;
    private Instant createdAt;
}
