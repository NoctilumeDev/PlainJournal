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
@TableName("chat_attachment_upload")
public class ChatAttachmentUploadEntity {
    @TableId
    private Long id;
    private Long conversationId;
    private Long uploaderId;
    private String clientUploadId;
    private String requestHash;
    private String objectKey;
    private String quarantineObjectKey;
    private String originalFilename;
    private String requestedMimeType;
    private Long requestedSizeBytes;
    private String verifiedMimeType;
    private Long verifiedSizeBytes;
    private String verifiedSha256;
    private String status;
    private Long messageId;
    private Instant expiresAt;
    private Instant cleanupClaimedAt;
    private Integer cleanupAttempts;
    private String cleanupLastError;
    private Instant cleanedAt;
    private Integer quarantineCleanupAttempts;
    private String quarantineCleanupLastError;
    private Instant quarantineCleanupClaimedAt;
    private Integer scanAttempts;
    private String scanClaimOwner;
    private Instant scanClaimedAt;
    private Instant scanClaimUntil;
    private String scanEngine;
    private String scanSignature;
    private String scanLastError;
    private Instant scanCompletedAt;
    private Instant createdAt;
    private Instant updatedAt;
}
