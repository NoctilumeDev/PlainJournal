package com.ecommerce.chat.interfaces.rest;

import com.ecommerce.chat.application.model.ChatModels.Actor;
import com.ecommerce.chat.application.model.ChatModels.AttachmentDownloadView;
import com.ecommerce.chat.application.model.ChatModels.AttachmentScanRetryAuditView;
import com.ecommerce.chat.application.model.ChatModels.AttachmentUploadView;
import com.ecommerce.chat.application.model.ChatModels.ConversationView;
import com.ecommerce.chat.application.model.ChatModels.CreateAttachmentUploadCommand;
import com.ecommerce.chat.application.model.ChatModels.CreateConversationCommand;
import com.ecommerce.chat.application.model.ChatModels.MessagePage;
import com.ecommerce.chat.application.model.ChatModels.MessageView;
import com.ecommerce.chat.application.model.ChatModels.ReadView;
import com.ecommerce.chat.application.model.ChatModels.RetryAttachmentScanCommand;
import com.ecommerce.chat.application.model.ChatModels.SendMessageCommand;
import com.ecommerce.chat.application.model.ChatModels.WebSocketTicketView;
import com.ecommerce.chat.application.port.ChatWebSocketTicketIssuer;
import com.ecommerce.chat.application.service.ChatApplicationService;
import com.ecommerce.chat.application.service.ChatAttachmentService;
import com.ecommerce.chat.application.service.ChatAttachmentScanService;
import com.ecommerce.platform.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final ChatApplicationService chatService;
    private final ChatAttachmentService attachmentService;
    private final ChatAttachmentScanService attachmentScanService;
    private final ChatWebSocketTicketIssuer webSocketTicketIssuer;

    public ChatController(
            ChatApplicationService chatService,
            ChatAttachmentService attachmentService,
            ChatAttachmentScanService attachmentScanService,
            ChatWebSocketTicketIssuer webSocketTicketIssuer) {
        this.chatService = chatService;
        this.attachmentService = attachmentService;
        this.attachmentScanService = attachmentScanService;
        this.webSocketTicketIssuer = webSocketTicketIssuer;
    }

    @PostMapping("/conversations")
    public ApiResponse<ConversationView> createConversation(
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication,
            @Valid @RequestBody CreateConversationRequest request) {
        return ApiResponse.success(chatService.createConversation(
                actor(jwt, authentication),
                request.toCommand()));
    }

    @GetMapping("/conversations")
    public ApiResponse<List<ConversationView>> listConversations(
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ApiResponse.success(chatService.listConversations(actor(jwt, authentication), limit));
    }

    @PostMapping("/websocket-tickets")
    public ResponseEntity<ApiResponse<WebSocketTicketView>> createWebSocketTicket(
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication) {
        Actor actor = actor(jwt, authentication);
        List<String> authorities = authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .toList();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(ApiResponse.success(webSocketTicketIssuer.issue(
                        actor.userId(),
                        authorities)));
    }

    @GetMapping("/conversations/{conversationId}")
    public ApiResponse<ConversationView> getConversation(
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication,
            @PathVariable @Positive Long conversationId) {
        return ApiResponse.success(chatService.getConversation(
                actor(jwt, authentication),
                conversationId));
    }

    @PostMapping("/conversations/{conversationId}/claim")
    public ApiResponse<ConversationView> claimConversation(
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication,
            @PathVariable @Positive Long conversationId) {
        return ApiResponse.success(chatService.claimConversation(
                actor(jwt, authentication),
                conversationId));
    }

    @PostMapping("/conversations/{conversationId}/close")
    public ApiResponse<ConversationView> closeConversation(
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication,
            @PathVariable @Positive Long conversationId) {
        return ApiResponse.success(chatService.closeConversation(
                actor(jwt, authentication),
                conversationId));
    }

    @PostMapping("/conversations/{conversationId}/messages")
    public ApiResponse<MessageView> sendMessage(
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication,
            @PathVariable @Positive Long conversationId,
            @Valid @RequestBody SendMessageRequest request) {
        return ApiResponse.success(chatService.sendMessage(
                actor(jwt, authentication),
                conversationId,
                request.toCommand()));
    }

    @PostMapping("/conversations/{conversationId}/attachments/upload-intents")
    public ApiResponse<AttachmentUploadView> createAttachmentUploadIntent(
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication,
            @PathVariable @Positive Long conversationId,
            @Valid @RequestBody CreateAttachmentUploadRequest request) {
        return ApiResponse.success(attachmentService.createUploadIntent(
                actor(jwt, authentication),
                conversationId,
                request.toCommand()));
    }

    @PostMapping("/conversations/{conversationId}/attachments/{uploadId}/confirm")
    public ApiResponse<AttachmentUploadView> confirmAttachmentUpload(
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication,
            @PathVariable @Positive Long conversationId,
            @PathVariable @Positive Long uploadId) {
        return ApiResponse.success(attachmentService.confirmUpload(
                actor(jwt, authentication),
                conversationId,
                uploadId));
    }

    @GetMapping("/conversations/{conversationId}/attachments/{uploadId}")
    public ApiResponse<AttachmentUploadView> getAttachmentUpload(
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication,
            @PathVariable @Positive Long conversationId,
            @PathVariable @Positive Long uploadId) {
        return ApiResponse.success(attachmentService.getUpload(
                actor(jwt, authentication),
                conversationId,
                uploadId));
    }

    @PostMapping("/admin/attachments/{uploadId}/scan-retries")
    public ApiResponse<AttachmentUploadView> retryAttachmentScan(
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication,
            @PathVariable @Positive Long uploadId,
            @Valid @RequestBody RetryAttachmentScanRequest request) {
        Actor actor = actor(jwt, authentication);
        return ApiResponse.success(attachmentScanService.retryScan(
                actor,
                new RetryAttachmentScanCommand(
                        request.commandId(),
                        uploadId,
                        actor.userId(),
                        request.reason())));
    }

    @GetMapping("/admin/attachments/{uploadId}/scan-retries")
    public ApiResponse<List<AttachmentScanRetryAuditView>> listAttachmentScanRetries(
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication,
            @PathVariable @Positive Long uploadId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ApiResponse.success(attachmentScanService.listRetryAudits(
                actor(jwt, authentication),
                uploadId,
                limit));
    }

    @GetMapping(
            "/conversations/{conversationId}/messages/{messageId}/attachments/{attachmentId}/download")
    public ApiResponse<AttachmentDownloadView> createAttachmentDownload(
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication,
            @PathVariable @Positive Long conversationId,
            @PathVariable @Positive Long messageId,
            @PathVariable @Positive Long attachmentId) {
        return ApiResponse.success(attachmentService.createDownload(
                actor(jwt, authentication),
                conversationId,
                messageId,
                attachmentId));
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public ApiResponse<MessagePage> listMessages(
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication,
            @PathVariable @Positive Long conversationId,
            @RequestParam(required = false) @Positive Long beforeSequence,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        return ApiResponse.success(chatService.listMessages(
                actor(jwt, authentication),
                conversationId,
                beforeSequence,
                size));
    }

    @PostMapping("/conversations/{conversationId}/read")
    public ApiResponse<ReadView> markRead(
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication,
            @PathVariable @Positive Long conversationId,
            @Valid @RequestBody ReadRequest request) {
        return ApiResponse.success(chatService.markRead(
                actor(jwt, authentication),
                conversationId,
                request.lastReadMessageId()));
    }

    private Actor actor(Jwt jwt, Authentication authentication) {
        try {
            Long userId = Long.valueOf(jwt.getSubject());
            boolean supportAgent = authentication.getAuthorities().stream()
                    .map(authority -> authority.getAuthority())
                    .anyMatch(authority -> "ROLE_ADMIN".equals(authority) || "ROLE_OPERATOR".equals(authority));
            return new Actor(userId, supportAgent);
        } catch (NumberFormatException exception) {
            throw new BadCredentialsException("JWT subject must be a numeric user ID", exception);
        }
    }

    public record CreateConversationRequest(
            @NotBlank
            @Size(max = 64)
            @Pattern(regexp = "[A-Za-z0-9._:-]+")
            String clientConversationId,
            @NotBlank @Size(max = 160) String subject,
            @Size(max = 32) String contextType,
            @Size(max = 80) String contextId
    ) {
        private CreateConversationCommand toCommand() {
            return new CreateConversationCommand(
                    clientConversationId,
                    subject,
                    contextType,
                    contextId);
        }
    }

    public record SendMessageRequest(
            @NotBlank
            @Size(max = 64)
            @Pattern(regexp = "[A-Za-z0-9._:-]+")
            String clientMessageId,
            @NotBlank @Size(max = 24) String messageType,
            @Size(max = 4000) String content,
            @Size(max = 5) List<@Positive Long> attachmentUploadIds
    ) {
        public SendMessageRequest {
            attachmentUploadIds = attachmentUploadIds == null
                    ? List.of()
                    : List.copyOf(attachmentUploadIds);
        }

        private SendMessageCommand toCommand() {
            return new SendMessageCommand(
                    clientMessageId,
                    messageType,
                    content,
                    attachmentUploadIds);
        }
    }

    public record CreateAttachmentUploadRequest(
            @NotBlank
            @Size(max = 64)
            @Pattern(regexp = "[A-Za-z0-9._:-]+")
            String clientUploadId,
            @NotBlank @Size(max = 255) String fileName,
            @NotBlank @Size(max = 120) String mimeType,
            @Positive long sizeBytes
    ) {
        private CreateAttachmentUploadCommand toCommand() {
            return new CreateAttachmentUploadCommand(
                    clientUploadId,
                    fileName,
                    mimeType,
                    sizeBytes);
        }
    }

    public record RetryAttachmentScanRequest(
            @NotBlank
            @Size(max = 64)
            @Pattern(regexp = "[A-Za-z0-9._:-]+")
            String commandId,
            @NotBlank @Size(min = 8, max = 500) String reason
    ) {
    }

    public record ReadRequest(
            @NotNull @Positive Long lastReadMessageId
    ) {
    }
}
