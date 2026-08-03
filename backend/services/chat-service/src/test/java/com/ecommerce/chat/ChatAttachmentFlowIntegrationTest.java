package com.ecommerce.chat;

import com.ecommerce.chat.application.exception.ChatError;
import com.ecommerce.chat.application.exception.ChatException;
import com.ecommerce.chat.application.model.ChatModels.Actor;
import com.ecommerce.chat.application.model.ChatModels.AttachmentUploadView;
import com.ecommerce.chat.application.model.ChatModels.CreateAttachmentUploadCommand;
import com.ecommerce.chat.application.port.ChatAttachmentMalwareScanner;
import com.ecommerce.chat.application.port.ChatAttachmentStorage;
import com.ecommerce.chat.application.service.ChatAttachmentCleanupService;
import com.ecommerce.chat.application.service.ChatAttachmentScanService;
import com.ecommerce.chat.application.service.ChatAttachmentService;
import com.ecommerce.chat.infrastructure.persistence.entity.ChatAttachmentUploadEntity;
import com.ecommerce.chat.infrastructure.persistence.mapper.ChatAttachmentUploadMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@ActiveProfiles("test")
@SpringBootTest
class ChatAttachmentFlowIntegrationTest {

    private static final long CUSTOMER_ID = 3001L;
    private static final long OTHER_CUSTOMER_ID = 3002L;
    private static final byte[] PNG_HEADER = new byte[]{
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            0x01, 0x02, 0x03, 0x04
    };

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ChatAttachmentService attachmentService;
    private final ChatAttachmentCleanupService cleanupService;
    private final ChatAttachmentScanService scanService;
    private final ChatAttachmentUploadMapper uploadMapper;

    @MockitoBean
    private ChatAttachmentStorage storage;

    @MockitoBean
    private ChatAttachmentMalwareScanner scanner;

    @Autowired
    ChatAttachmentFlowIntegrationTest(
            MockMvc mockMvc,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate,
            ChatAttachmentService attachmentService,
            ChatAttachmentCleanupService cleanupService,
            ChatAttachmentScanService scanService,
            ChatAttachmentUploadMapper uploadMapper) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.attachmentService = attachmentService;
        this.cleanupService = cleanupService;
        this.scanService = scanService;
        this.uploadMapper = uploadMapper;
    }

    @AfterEach
    void cleanData() {
        jdbcTemplate.update("DELETE FROM message_receipt");
        jdbcTemplate.update("DELETE FROM chat_attachment");
        jdbcTemplate.update("DELETE FROM chat_attachment_scan_retry_audit");
        jdbcTemplate.update("DELETE FROM chat_attachment_upload");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM chat_message");
        jdbcTemplate.update("DELETE FROM conversation_member");
        jdbcTemplate.update("DELETE FROM chat_conversation");
        reset(storage, scanner);
    }

    @Test
    void confirmsAndAtomicallyBindsAttachmentWithAuthorizedDownload() throws Exception {
        String conversationId = createConversation("attachment-conversation-1");
        when(storage.createUploadUrl(eq("chat-attachments"), any(), any()))
                .thenReturn("http://minio.test/upload");
        when(storage.inspect(eq("chat-attachments"), any(), anyInt(), anyLong()))
                .thenReturn(new ChatAttachmentStorage.StoredObject(
                        PNG_HEADER.length,
                        "image/png",
                        PNG_HEADER));
        when(storage.open(eq("chat-attachments"), any()))
                .thenAnswer(invocation -> new ByteArrayInputStream(PNG_HEADER));
        when(scanner.scan(any(), anyLong()))
                .thenReturn(cleanResult(PNG_HEADER));
        when(storage.createDownloadUrl(eq("chat-attachments"), any(), any()))
                .thenReturn("http://minio.test/download");

        JsonNode upload = responseJson(mockMvc.perform(post(
                                "/api/v1/chat/conversations/{conversationId}/attachments/upload-intents",
                                conversationId)
                        .with(customerJwt(CUSTOMER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "clientUploadId", "upload-1",
                                "fileName", "parcel.png",
                                "mimeType", "image/png",
                                "sizeBytes", PNG_HEADER.length
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.uploadUrl").value("http://minio.test/upload"))
                .andReturn().getResponse().getContentAsString());
        String uploadId = upload.at("/data/id").asText();
        String sourceObjectKey = jdbcTemplate.queryForObject(
                "SELECT object_key FROM chat_attachment_upload WHERE id = ?",
                String.class,
                Long.valueOf(uploadId));

        mockMvc.perform(post(
                        "/api/v1/chat/conversations/{conversationId}/attachments/{uploadId}/confirm",
                        conversationId,
                        uploadId)
                        .with(customerJwt(CUSTOMER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SCAN_PENDING"))
                .andExpect(jsonPath("$.data.uploadUrl").doesNotExist());
        String sealedObjectKey = jdbcTemplate.queryForObject(
                "SELECT object_key FROM chat_attachment_upload WHERE id = ?",
                String.class,
                Long.valueOf(uploadId));
        assertThat(sourceObjectKey).startsWith("quarantine/chat/test/");
        assertThat(sealedObjectKey).startsWith("objects/chat/test/").isNotEqualTo(sourceObjectKey);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT quarantine_object_key FROM chat_attachment_upload WHERE id = ?",
                String.class,
                Long.valueOf(uploadId))).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT quarantine_cleanup_attempts FROM chat_attachment_upload WHERE id = ?",
                Integer.class,
                Long.valueOf(uploadId))).isEqualTo(1);
        verify(storage).copyIfUnchanged(
                eq("chat-attachments"),
                eq(sourceObjectKey),
                eq(sealedObjectKey),
                eq(sha256(PNG_HEADER)));
        verify(storage).remove(eq("chat-attachments"), eq(sourceObjectKey));
        assertThat(scanService.scanBatch()).isEqualTo(1);
        mockMvc.perform(get(
                                "/api/v1/chat/conversations/{conversationId}/attachments/{uploadId}",
                                conversationId,
                                uploadId)
                        .with(customerJwt(CUSTOMER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.scanAttempts").value(1))
                .andExpect(jsonPath("$.data.scanEngine").value("ClamAV"));

        JsonNode message = responseJson(mockMvc.perform(post(
                                "/api/v1/chat/conversations/{conversationId}/messages",
                                conversationId)
                        .with(customerJwt(CUSTOMER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "clientMessageId", "attachment-message-1",
                                "messageType", "IMAGE",
                                "content", "Parcel photo",
                                "attachmentUploadIds", List.of(uploadId)
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messageType").value("IMAGE"))
                .andExpect(jsonPath("$.data.attachments.length()").value(1))
                .andExpect(jsonPath("$.data.attachments[0].fileName").value("parcel.png"))
                .andExpect(jsonPath("$.data.attachments[0].mimeType").value("image/png"))
                .andReturn().getResponse().getContentAsString());
        String messageId = message.at("/data/id").asText();
        String attachmentId = message.at("/data/attachments/0/id").asText();

        assertThat(count("chat_attachment_upload")).isEqualTo(1);
        assertThat(count("chat_attachment")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM chat_attachment_upload WHERE id = ?",
                String.class,
                Long.valueOf(uploadId))).isEqualTo("ATTACHED");

        mockMvc.perform(post("/api/v1/chat/conversations/{conversationId}/messages", conversationId)
                        .with(customerJwt(CUSTOMER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "clientMessageId", "attachment-message-1",
                                "messageType", "IMAGE",
                                "content", "Parcel photo",
                                "attachmentUploadIds", List.of(uploadId)
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(messageId))
                .andExpect(jsonPath("$.data.attachments[0].id").value(attachmentId));
        assertThat(count("chat_message")).isEqualTo(1);
        assertThat(count("chat_attachment")).isEqualTo(1);

        mockMvc.perform(get(
                                "/api/v1/chat/conversations/{conversationId}/messages",
                                conversationId)
                        .with(customerJwt(CUSTOMER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].attachments[0].id").value(attachmentId));

        mockMvc.perform(get(
                                "/api/v1/chat/conversations/{conversationId}/messages/{messageId}"
                                        + "/attachments/{attachmentId}/download",
                                conversationId,
                                messageId,
                                attachmentId)
                        .with(customerJwt(OTHER_CUSTOMER_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONVERSATION_ACCESS_DENIED"));

        mockMvc.perform(get(
                                "/api/v1/chat/conversations/{conversationId}/messages/{messageId}"
                                        + "/attachments/{attachmentId}/download",
                                conversationId,
                                messageId,
                                attachmentId)
                        .with(customerJwt(CUSTOMER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attachmentId").value(attachmentId))
                .andExpect(jsonPath("$.data.downloadUrl").value("http://minio.test/download"))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(300));

        byte[] tamperedBytes = PNG_HEADER.clone();
        tamperedBytes[tamperedBytes.length - 1] = 0x05;
        when(storage.inspect(
                eq("chat-attachments"), eq(sourceObjectKey), anyInt(), anyLong()))
                .thenReturn(new ChatAttachmentStorage.StoredObject(
                        tamperedBytes.length,
                        "image/png",
                        tamperedBytes));
        mockMvc.perform(get(
                                "/api/v1/chat/conversations/{conversationId}/messages/{messageId}"
                                        + "/attachments/{attachmentId}/download",
                                conversationId,
                                messageId,
                         attachmentId)
                        .with(customerJwt(CUSTOMER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.downloadUrl").value("http://minio.test/download"));

        when(storage.inspect(
                eq("chat-attachments"), eq(sealedObjectKey), anyInt(), anyLong()))
                .thenReturn(new ChatAttachmentStorage.StoredObject(
                        tamperedBytes.length,
                        "image/png",
                        tamperedBytes));
        mockMvc.perform(get(
                                "/api/v1/chat/conversations/{conversationId}/messages/{messageId}"
                                        + "/attachments/{attachmentId}/download",
                                conversationId,
                                messageId,
                                attachmentId)
                        .with(customerJwt(CUSTOMER_ID)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ATTACHMENT_OBJECT_MISMATCH"));

        mockMvc.perform(post("/api/v1/chat/conversations/{conversationId}/messages", conversationId)
                        .with(customerJwt(CUSTOMER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "clientMessageId", "attachment-message-2",
                                "messageType", "IMAGE",
                                "content", "Reuse must fail",
                                "attachmentUploadIds", List.of(uploadId)
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ATTACHMENT_ALREADY_ATTACHED"));
    }

    @Test
    void rejectsMismatchedObjectAndUploadIdempotencyConflict() throws Exception {
        String conversationId = createConversation("attachment-conversation-2");
        when(storage.createUploadUrl(eq("chat-attachments"), any(), any()))
                .thenReturn("http://minio.test/upload");
        when(storage.inspect(eq("chat-attachments"), any(), anyInt(), anyLong()))
                .thenReturn(new ChatAttachmentStorage.StoredObject(
                        PNG_HEADER.length,
                        "image/png",
                        new byte[]{
                                0x00, 0x01, 0x02, 0x03,
                                0x04, 0x05, 0x06, 0x07,
                                0x08, 0x09, 0x0a, 0x0b
                        }));

        JsonNode upload = responseJson(mockMvc.perform(post(
                                "/api/v1/chat/conversations/{conversationId}/attachments/upload-intents",
                                conversationId)
                        .with(customerJwt(CUSTOMER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "clientUploadId", "upload-mismatch",
                                "fileName", "proof.png",
                                "mimeType", "image/png",
                                "sizeBytes", PNG_HEADER.length
                        ))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        String uploadId = upload.at("/data/id").asText();

        mockMvc.perform(post(
                                "/api/v1/chat/conversations/{conversationId}/attachments/{uploadId}/confirm",
                                conversationId,
                                uploadId)
                        .with(customerJwt(CUSTOMER_ID)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ATTACHMENT_OBJECT_MISMATCH"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM chat_attachment_upload WHERE id = ?",
                String.class,
                Long.valueOf(uploadId))).isEqualTo("PENDING");

        mockMvc.perform(post(
                                "/api/v1/chat/conversations/{conversationId}/attachments/upload-intents",
                                conversationId)
                        .with(customerJwt(CUSTOMER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "clientUploadId", "upload-mismatch",
                                "fileName", "proof.png",
                                "mimeType", "image/png",
                                "sizeBytes", PNG_HEADER.length + 1
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void removesSealedObjectWhenDatabaseConfirmationCannotCommit() throws Exception {
        String conversationId = createConversation("attachment-confirmation-compensation");
        when(storage.createUploadUrl(eq("chat-attachments"), any(), any()))
                .thenReturn("http://minio.test/upload");
        when(storage.inspect(eq("chat-attachments"), any(), anyInt(), anyLong()))
                .thenReturn(new ChatAttachmentStorage.StoredObject(
                        PNG_HEADER.length,
                        "image/png",
                        PNG_HEADER));

        AttachmentUploadView upload = attachmentService.createUploadIntent(
                new Actor(CUSTOMER_ID, false),
                Long.valueOf(conversationId),
                new CreateAttachmentUploadCommand(
                        "upload-confirmation-compensation",
                        "compensation.png",
                        "image/png",
                        PNG_HEADER.length));
        AtomicReference<String> sealedObjectKey = new AtomicReference<>();
        doAnswer(invocation -> {
            sealedObjectKey.set(invocation.getArgument(2));
            jdbcTemplate.update(
                    "UPDATE chat_attachment_upload SET status = 'DELETED' WHERE id = ?",
                    upload.id());
            return null;
        }).when(storage).copyIfUnchanged(
                eq("chat-attachments"),
                any(),
                any(),
                any());

        assertThatThrownBy(() -> attachmentService.confirmUpload(
                new Actor(CUSTOMER_ID, false),
                Long.valueOf(conversationId),
                upload.id()))
                .isInstanceOf(ChatException.class)
                .satisfies(error -> assertThat(((ChatException) error).error())
                        .isEqualTo(ChatError.ATTACHMENT_NOT_READY));

        assertThat(sealedObjectKey.get()).startsWith("objects/chat/test/");
        verify(storage).remove("chat-attachments", sealedObjectKey.get());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM chat_attachment_upload WHERE id = ?",
                String.class,
                upload.id())).isEqualTo("DELETED");
    }

    @Test
    void retriesQuarantineSourceCleanupWithoutDeletingSealedObject() throws Exception {
        String conversationId = createConversation("attachment-quarantine-cleanup");
        when(storage.createUploadUrl(eq("chat-attachments"), any(), any()))
                .thenReturn("http://minio.test/upload");
        when(storage.inspect(eq("chat-attachments"), any(), anyInt(), anyLong()))
                .thenReturn(new ChatAttachmentStorage.StoredObject(
                        PNG_HEADER.length,
                        "image/png",
                        PNG_HEADER));

        AttachmentUploadView upload = attachmentService.createUploadIntent(
                new Actor(CUSTOMER_ID, false),
                Long.valueOf(conversationId),
                new CreateAttachmentUploadCommand(
                        "quarantine-cleanup",
                        "quarantine.png",
                        "image/png",
                        PNG_HEADER.length));
        String sourceObjectKey = jdbcTemplate.queryForObject(
                "SELECT object_key FROM chat_attachment_upload WHERE id = ?",
                String.class,
                upload.id());
        doThrow(new ChatException(ChatError.ATTACHMENT_STORAGE_UNAVAILABLE))
                .when(storage).remove("chat-attachments", sourceObjectKey);

        AttachmentUploadView confirmed = attachmentService.confirmUpload(
                new Actor(CUSTOMER_ID, false),
                Long.valueOf(conversationId),
                upload.id());
        String sealedObjectKey = jdbcTemplate.queryForObject(
                "SELECT object_key FROM chat_attachment_upload WHERE id = ?",
                String.class,
                upload.id());

        assertThat(confirmed.status()).isEqualTo("SCAN_PENDING");
        assertThat(sealedObjectKey).startsWith("objects/chat/test/");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT quarantine_object_key FROM chat_attachment_upload WHERE id = ?",
                String.class,
                upload.id())).isEqualTo(sourceObjectKey);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT quarantine_cleanup_attempts FROM chat_attachment_upload WHERE id = ?",
                Integer.class,
                upload.id())).isEqualTo(1);

        reset(storage);
        doNothing().when(storage).remove("chat-attachments", sourceObjectKey);
        assertThat(cleanupService.cleanupQuarantineBatch()).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT quarantine_object_key FROM chat_attachment_upload WHERE id = ?",
                String.class,
                upload.id())).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT quarantine_cleanup_attempts FROM chat_attachment_upload WHERE id = ?",
                Integer.class,
                upload.id())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT quarantine_cleanup_claimed_at FROM chat_attachment_upload WHERE id = ?",
                Instant.class,
                upload.id())).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT object_key FROM chat_attachment_upload WHERE id = ?",
                String.class,
                upload.id())).isEqualTo(sealedObjectKey);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM chat_attachment_upload WHERE id = ?",
                String.class,
                upload.id())).isEqualTo("SCAN_PENDING");
        verify(storage).remove("chat-attachments", sourceObjectKey);
    }

    @Test
    void keepsPendingIntentWhenStorageCannotIssueUploadUrl() throws Exception {
        String conversationId = createConversation("attachment-conversation-3");
        when(storage.createUploadUrl(eq("chat-attachments"), any(), any()))
                .thenThrow(new ChatException(ChatError.ATTACHMENT_STORAGE_UNAVAILABLE));

        mockMvc.perform(post(
                                "/api/v1/chat/conversations/{conversationId}/attachments/upload-intents",
                                conversationId)
                        .with(customerJwt(CUSTOMER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "clientUploadId", "upload-storage-down",
                                "fileName", "evidence.pdf",
                                "mimeType", "application/pdf",
                                "sizeBytes", 5
                        ))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ATTACHMENT_STORAGE_UNAVAILABLE"));

        assertThat(count("chat_attachment_upload")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM chat_attachment_upload",
                String.class)).isEqualTo("PENDING");
        assertThat(count("chat_message")).isZero();
    }

    @Test
    void infectedAndUnavailableScansRemainClosedUntilAuditedRetry() throws Exception {
        String conversationId = createConversation("attachment-conversation-scan");
        byte[] eicar = ("X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!"
                + "$H+H*").getBytes(StandardCharsets.US_ASCII);
        when(storage.createUploadUrl(eq("chat-attachments"), any(), any()))
                .thenReturn("http://minio.test/upload");
        when(storage.inspect(eq("chat-attachments"), any(), anyInt(), anyLong()))
                .thenReturn(new ChatAttachmentStorage.StoredObject(
                        eicar.length,
                        "text/plain",
                        eicar));
        when(storage.open(eq("chat-attachments"), any()))
                .thenAnswer(invocation -> new ByteArrayInputStream(eicar));
        when(scanner.scan(any(), anyLong()))
                .thenReturn(new ChatAttachmentMalwareScanner.ScanResult(
                        ChatAttachmentMalwareScanner.Verdict.INFECTED,
                        "ClamAV",
                        "Win.Test.EICAR_HDB-1",
                        eicar.length,
                        sha256(eicar)));

        String infectedUploadId = createAndConfirmUpload(
                conversationId,
                "infected-upload",
                "eicar.txt",
                "text/plain",
                eicar.length);
        assertThat(scanService.scanBatch()).isEqualTo(1);
        mockMvc.perform(get(
                                "/api/v1/chat/conversations/{conversationId}/attachments/{uploadId}",
                                conversationId,
                                infectedUploadId)
                        .with(customerJwt(CUSTOMER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INFECTED"))
                .andExpect(jsonPath("$.data.scanSignature").value("Win.Test.EICAR_HDB-1"));
        mockMvc.perform(post("/api/v1/chat/conversations/{conversationId}/messages", conversationId)
                        .with(customerJwt(CUSTOMER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "clientMessageId", "infected-message",
                                "messageType", "FILE",
                                "content", "must remain blocked",
                                "attachmentUploadIds", List.of(infectedUploadId)
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ATTACHMENT_INFECTED"));
        assertThat(count("chat_message")).isZero();

        reset(scanner);
        when(scanner.scan(any(), anyLong()))
                .thenThrow(new IllegalStateException("scanner unavailable"));
        String retryUploadId = createAndConfirmUpload(
                conversationId,
                "retry-upload",
                "retry.txt",
                "text/plain",
                eicar.length);
        assertThat(scanService.scanBatch()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM chat_attachment_upload WHERE id = ?",
                String.class,
                Long.valueOf(retryUploadId))).isEqualTo("SCAN_RETRY");
        assertThat(scanService.scanBatch()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM chat_attachment_upload WHERE id = ?",
                String.class,
                Long.valueOf(retryUploadId))).isEqualTo("SCAN_NEEDS_ATTENTION");

        String retryCommand = json(Map.of(
                "commandId", "scan-retry-command-1",
                "reason", "ClamAV connectivity was restored"
        ));
        mockMvc.perform(post(
                                "/api/v1/chat/admin/attachments/{uploadId}/scan-retries",
                                retryUploadId)
                        .with(customerJwt(CUSTOMER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(retryCommand))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(
                                "/api/v1/chat/admin/attachments/{uploadId}/scan-retries",
                                retryUploadId)
                        .with(adminJwt(9001L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(retryCommand))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SCAN_PENDING"))
                .andExpect(jsonPath("$.data.scanAttempts").value(0));
        mockMvc.perform(post(
                                "/api/v1/chat/admin/attachments/{uploadId}/scan-retries",
                                retryUploadId)
                        .with(adminJwt(9001L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(retryCommand))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SCAN_PENDING"));
        assertThat(count("chat_attachment_scan_retry_audit")).isEqualTo(1);

        reset(scanner);
        when(scanner.scan(any(), anyLong()))
                .thenReturn(cleanResult(eicar));
        assertThat(scanService.scanBatch()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM chat_attachment_upload WHERE id = ?",
                String.class,
                Long.valueOf(retryUploadId))).isEqualTo("READY");
        mockMvc.perform(get(
                                "/api/v1/chat/admin/attachments/{uploadId}/scan-retries",
                                retryUploadId)
                        .with(adminJwt(9001L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].outcome").value("ACCEPTED"))
                .andExpect(jsonPath("$.data[0].beforeStatus")
                        .value("SCAN_NEEDS_ATTENTION"));
    }

    @Test
    void concurrentUploadIntentRetriesConvergeToOneFact() throws Exception {
        Long conversationId = Long.valueOf(createConversation("attachment-conversation-4"));
        when(storage.createUploadUrl(eq("chat-attachments"), any(), any()))
                .thenReturn("http://minio.test/upload");
        CreateAttachmentUploadCommand command = new CreateAttachmentUploadCommand(
                "concurrent-upload",
                "concurrent.png",
                "image/png",
                PNG_HEADER.length);

        List<AttachmentUploadView> uploads = runConcurrently(
                8,
                () -> attachmentService.createUploadIntent(
                        new Actor(CUSTOMER_ID, false),
                        conversationId,
                        command));

        assertThat(uploads)
                .extracting(AttachmentUploadView::id)
                .containsOnly(uploads.get(0).id());
        assertThat(count("chat_attachment_upload")).isEqualTo(1);
    }

    @Test
    void expiredOrphanCleanupIsClaimedAndRetriedWithoutDeletingAttachedFacts() throws Exception {
        Long conversationId = Long.valueOf(createConversation("attachment-conversation-5"));
        when(storage.createUploadUrl(eq("chat-attachments"), any(), any()))
                .thenReturn("http://minio.test/upload");
        AttachmentUploadView first = attachmentService.createUploadIntent(
                new Actor(CUSTOMER_ID, false),
                conversationId,
                new CreateAttachmentUploadCommand(
                        "cleanup-success",
                        "cleanup.png",
                        "image/png",
                        PNG_HEADER.length));
        jdbcTemplate.update(
                "UPDATE chat_attachment_upload SET expires_at = ? WHERE id = ?",
                Instant.now().minusSeconds(60),
                first.id());
        doNothing().when(storage).remove(eq("chat-attachments"), any());

        assertThat(cleanupService.cleanupBatch()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM chat_attachment_upload WHERE id = ?",
                String.class,
                first.id())).isEqualTo("DELETED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT cleanup_attempts FROM chat_attachment_upload WHERE id = ?",
                Integer.class,
                first.id())).isEqualTo(1);
        verify(storage).remove(eq("chat-attachments"), any());

        AttachmentUploadView retryable = attachmentService.createUploadIntent(
                new Actor(CUSTOMER_ID, false),
                conversationId,
                new CreateAttachmentUploadCommand(
                        "cleanup-retry",
                        "retry.pdf",
                        "application/pdf",
                        5));
        jdbcTemplate.update(
                "UPDATE chat_attachment_upload SET expires_at = ? WHERE id = ?",
                Instant.now().minusSeconds(60),
                retryable.id());
        doThrow(new ChatException(ChatError.ATTACHMENT_STORAGE_UNAVAILABLE))
                .when(storage).remove(eq("chat-attachments"), any());

        assertThat(cleanupService.cleanupBatch()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM chat_attachment_upload WHERE id = ?",
                String.class,
                retryable.id())).isEqualTo("CLEANUP_PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT cleanup_attempts FROM chat_attachment_upload WHERE id = ?",
                Integer.class,
                retryable.id())).isEqualTo(1);

        reset(storage);
        doNothing().when(storage).remove(eq("chat-attachments"), any());
        assertThat(cleanupService.cleanupBatch()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM chat_attachment_upload WHERE id = ?",
                String.class,
                retryable.id())).isEqualTo("DELETED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT cleanup_attempts FROM chat_attachment_upload WHERE id = ?",
                Integer.class,
                retryable.id())).isEqualTo(2);
    }

    @Test
    void staleAttachmentScanAndCleanupCandidatesCannotMutateANewerClaim() throws Exception {
        String conversationId = createConversation("attachment-fencing");
        when(storage.createUploadUrl(eq("chat-attachments"), any(), any()))
                .thenReturn("http://minio.test/upload");
        when(storage.inspect(eq("chat-attachments"), any(), anyInt(), anyLong()))
                .thenReturn(new ChatAttachmentStorage.StoredObject(
                        PNG_HEADER.length,
                        "image/png",
                        PNG_HEADER));
        Long scanUploadId = Long.valueOf(createAndConfirmUpload(
                conversationId,
                "scan-fencing",
                "scan.png",
                "image/png",
                PNG_HEADER.length));

        Instant scanClaimedAt = Instant.now().plusSeconds(1);
        ChatAttachmentUploadEntity staleScan =
                uploadMapper.selectScanCandidates(scanClaimedAt, 20).stream()
                        .filter(candidate -> candidate.getId().equals(scanUploadId))
                        .findFirst()
                        .orElseThrow();
        assertThat(uploadMapper.claimScan(
                scanUploadId,
                "scanner-a",
                staleScan.getScanAttempts(),
                scanClaimedAt,
                scanClaimedAt.plusSeconds(5))).isEqualTo(1);
        assertThat(uploadMapper.markScanReady(
                scanUploadId,
                "scanner-a",
                1,
                "ClamAV",
                scanClaimedAt.plusSeconds(6))).isZero();
        assertThat(uploadMapper.claimScan(
                scanUploadId,
                "scanner-b",
                staleScan.getScanAttempts(),
                scanClaimedAt.plusSeconds(6),
                scanClaimedAt.plusSeconds(36))).isZero();
        ChatAttachmentUploadEntity freshScan =
                uploadMapper.selectScanCandidates(scanClaimedAt.plusSeconds(6), 20).stream()
                        .filter(candidate -> candidate.getId().equals(scanUploadId))
                        .findFirst()
                        .orElseThrow();
        assertThat(freshScan.getScanAttempts()).isEqualTo(1);
        assertThat(uploadMapper.claimScan(
                scanUploadId,
                "scanner-b",
                freshScan.getScanAttempts(),
                scanClaimedAt.plusSeconds(6),
                scanClaimedAt.plusSeconds(36))).isEqualTo(1);

        AttachmentUploadView cleanupUpload = attachmentService.createUploadIntent(
                new Actor(CUSTOMER_ID, false),
                Long.valueOf(conversationId),
                new CreateAttachmentUploadCommand(
                        "cleanup-fencing",
                        "cleanup.png",
                        "image/png",
                        PNG_HEADER.length));
        Instant cleanupAt = Instant.now().plusSeconds(1);
        jdbcTemplate.update(
                "UPDATE chat_attachment_upload SET expires_at = ? WHERE id = ?",
                cleanupAt.minusSeconds(1),
                cleanupUpload.id());
        Instant recoverBefore = cleanupAt.minusSeconds(30);
        ChatAttachmentUploadEntity staleCleanup =
                uploadMapper.selectCleanupCandidates(cleanupAt, recoverBefore, 20).stream()
                        .filter(candidate -> candidate.getId().equals(cleanupUpload.id()))
                        .findFirst()
                        .orElseThrow();
        assertThat(uploadMapper.claimCleanup(
                cleanupUpload.id(),
                staleCleanup.getCleanupAttempts(),
                cleanupAt,
                recoverBefore)).isEqualTo(1);
        assertThat(uploadMapper.markCleanupPending(
                cleanupUpload.id(),
                1,
                "fault injected",
                cleanupAt.plusSeconds(1))).isEqualTo(1);
        assertThat(uploadMapper.claimCleanup(
                cleanupUpload.id(),
                staleCleanup.getCleanupAttempts(),
                cleanupAt.plusSeconds(2),
                recoverBefore)).isZero();
        ChatAttachmentUploadEntity freshCleanup =
                uploadMapper.selectCleanupCandidates(
                                cleanupAt.plusSeconds(2),
                                recoverBefore,
                                20)
                        .stream()
                        .filter(candidate -> candidate.getId().equals(cleanupUpload.id()))
                        .findFirst()
                        .orElseThrow();
        assertThat(freshCleanup.getCleanupAttempts()).isEqualTo(1);
        assertThat(uploadMapper.claimCleanup(
                cleanupUpload.id(),
                freshCleanup.getCleanupAttempts(),
                cleanupAt.plusSeconds(2),
                recoverBefore)).isEqualTo(1);
        assertThat(uploadMapper.markDeleted(
                cleanupUpload.id(),
                1,
                cleanupAt.plusSeconds(3))).isZero();
        assertThat(uploadMapper.markDeleted(
                cleanupUpload.id(),
                2,
                cleanupAt.plusSeconds(3))).isEqualTo(1);

        String quarantineObjectKey = "quarantine/chat/test/fenced-source.png";
        jdbcTemplate.update("""
                UPDATE chat_attachment_upload
                SET quarantine_object_key = ?,
                    quarantine_cleanup_attempts = 0,
                    quarantine_cleanup_claimed_at = NULL
                WHERE id = ?
                """, quarantineObjectKey, cleanupUpload.id());
        Instant quarantineClaimedAt = cleanupAt.plusSeconds(4);
        Instant quarantineRecoverBefore = quarantineClaimedAt.minusSeconds(30);
        assertThat(uploadMapper.claimQuarantineCleanup(
                cleanupUpload.id(),
                quarantineObjectKey,
                0,
                quarantineClaimedAt,
                quarantineRecoverBefore)).isEqualTo(1);
        assertThat(uploadMapper.claimQuarantineCleanup(
                cleanupUpload.id(),
                quarantineObjectKey,
                0,
                quarantineClaimedAt.plusSeconds(1),
                quarantineRecoverBefore)).isZero();
        assertThat(uploadMapper.selectQuarantineCleanupCandidates(
                        quarantineRecoverBefore,
                        20).stream()
                .noneMatch(candidate -> candidate.getId().equals(cleanupUpload.id())))
                .isTrue();

        Instant recoveredClaimedAt = quarantineClaimedAt.plusSeconds(31);
        assertThat(uploadMapper.claimQuarantineCleanup(
                cleanupUpload.id(),
                quarantineObjectKey,
                1,
                recoveredClaimedAt,
                quarantineClaimedAt.plusSeconds(1))).isEqualTo(1);
        assertThat(uploadMapper.markClaimedQuarantineCleaned(
                cleanupUpload.id(),
                quarantineObjectKey,
                1,
                recoveredClaimedAt.plusSeconds(1))).isZero();
        assertThat(uploadMapper.markClaimedQuarantineCleaned(
                cleanupUpload.id(),
                quarantineObjectKey,
                2,
                recoveredClaimedAt.plusSeconds(1))).isEqualTo(1);
    }

    private String createConversation(String clientConversationId) throws Exception {
        return responseJson(mockMvc.perform(post("/api/v1/chat/conversations")
                        .with(customerJwt(CUSTOMER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "clientConversationId", clientConversationId,
                                "subject", "Attachment verification"
                        ))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString())
                .at("/data/id").asText();
    }

    private String createAndConfirmUpload(
            String conversationId,
            String clientUploadId,
            String fileName,
            String mimeType,
            long sizeBytes) throws Exception {
        String uploadId = responseJson(mockMvc.perform(post(
                                "/api/v1/chat/conversations/{conversationId}/attachments/upload-intents",
                                conversationId)
                        .with(customerJwt(CUSTOMER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "clientUploadId", clientUploadId,
                                "fileName", fileName,
                                "mimeType", mimeType,
                                "sizeBytes", sizeBytes
                        ))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString())
                .at("/data/id").asText();
        mockMvc.perform(post(
                                "/api/v1/chat/conversations/{conversationId}/attachments/{uploadId}/confirm",
                                conversationId,
                                uploadId)
                        .with(customerJwt(CUSTOMER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SCAN_PENDING"));
        return uploadId;
    }

    private RequestPostProcessor customerJwt(long userId) {
        return jwt()
                .jwt(token -> token.subject(Long.toString(userId)).claim("roles", List.of("CUSTOMER")))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private RequestPostProcessor adminJwt(long userId) {
        return jwt()
                .jwt(token -> token.subject(Long.toString(userId)).claim("roles", List.of("ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private ChatAttachmentMalwareScanner.ScanResult cleanResult(byte[] content) {
        return new ChatAttachmentMalwareScanner.ScanResult(
                ChatAttachmentMalwareScanner.Verdict.CLEAN,
                "ClamAV",
                null,
                content.length,
                sha256(content));
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private JsonNode responseJson(String value) throws Exception {
        return objectMapper.readTree(value);
    }

    private <T> List<T> runConcurrently(int participants, Callable<T> action) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(participants);
        CountDownLatch ready = new CountDownLatch(participants);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = java.util.stream.IntStream.range(0, participants)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Concurrent attachment test start timed out");
                        }
                        return action.call();
                    }))
                    .toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return futures.stream()
                    .map(future -> {
                        try {
                            return future.get(20, TimeUnit.SECONDS);
                        } catch (Exception exception) {
                            throw new IllegalStateException(
                                    "Concurrent attachment request failed",
                                    exception);
                        }
                    })
                    .toList();
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
