package com.ecommerce.chat.application.model;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.Instant;
import java.util.List;

public final class ChatModels {

    private ChatModels() {
    }

    public record Actor(Long userId, boolean supportAgent) {
    }

    public record CreateConversationCommand(
            String clientConversationId,
            String subject,
            String contextType,
            String contextId
    ) {
    }

    public record SendMessageCommand(
            String clientMessageId,
            String messageType,
            String content,
            List<Long> attachmentUploadIds
    ) {
        public SendMessageCommand(String clientMessageId, String messageType, String content) {
            this(clientMessageId, messageType, content, List.of());
        }

        public SendMessageCommand {
            attachmentUploadIds = attachmentUploadIds == null
                    ? List.of()
                    : List.copyOf(attachmentUploadIds);
        }
    }

    public record CreateAttachmentUploadCommand(
            String clientUploadId,
            String fileName,
            String mimeType,
            long sizeBytes
    ) {
    }

    public record AttachmentUploadView(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            @JsonSerialize(using = ToStringSerializer.class) Long conversationId,
            String clientUploadId,
            String fileName,
            String mimeType,
            long sizeBytes,
            String status,
            String uploadUrl,
            Instant expiresAt,
            int scanAttempts,
            String scanEngine,
            String scanSignature,
            Instant scanCompletedAt
    ) {
    }

    public record RetryAttachmentScanCommand(
            String commandId,
            Long uploadId,
            Long operatorId,
            String reason
    ) {
    }

    public record AttachmentScanRetryAuditView(
            String commandId,
            @JsonSerialize(using = ToStringSerializer.class) Long uploadId,
            @JsonSerialize(using = ToStringSerializer.class) Long operatorId,
            String reason,
            String beforeStatus,
            Integer beforeAttempts,
            String beforeLastError,
            String outcome,
            String errorCode,
            String afterStatus,
            Integer afterAttempts,
            Instant createdAt
    ) {
    }

    public record AttachmentView(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String fileName,
            String mimeType,
            long sizeBytes
    ) {
    }

    public record AttachmentDownloadView(
            @JsonSerialize(using = ToStringSerializer.class) Long attachmentId,
            String downloadUrl,
            long expiresInSeconds
    ) {
    }

    public record WebSocketTicketView(
            String ticket,
            String targetPath,
            String queryParameter,
            Instant expiresAt
    ) {
    }

    public record WebSocketTicketIdentity(
            Long userId,
            List<String> roles
    ) {
        public WebSocketTicketIdentity {
            roles = roles == null ? List.of() : List.copyOf(roles);
        }
    }

    public record ConversationView(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String conversationNo,
            @JsonSerialize(using = ToStringSerializer.class) Long customerId,
            @JsonSerialize(using = ToStringSerializer.class) Long assignedAgentId,
            String subject,
            String contextType,
            String contextId,
            String status,
            long lastMessageSequence,
            long unreadCount,
            int version,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record MessageView(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            @JsonSerialize(using = ToStringSerializer.class) Long conversationId,
            @JsonSerialize(using = ToStringSerializer.class) Long senderId,
            String clientMessageId,
            long sequence,
            String messageType,
            String content,
            List<AttachmentView> attachments,
            String status,
            Instant createdAt
    ) {
        public MessageView {
            attachments = List.copyOf(attachments);
        }
    }

    public record MessagePage(
            List<MessageView> items,
            Long nextBeforeSequence,
            boolean hasMore
    ) {
        public MessagePage {
            items = List.copyOf(items);
        }
    }

    public record ReadView(
            @JsonSerialize(using = ToStringSerializer.class) Long conversationId,
            @JsonSerialize(using = ToStringSerializer.class) Long lastReadMessageId,
            long lastReadSequence,
            Instant readAt
    ) {
    }
}
