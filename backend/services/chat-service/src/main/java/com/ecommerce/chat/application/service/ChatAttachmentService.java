package com.ecommerce.chat.application.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ecommerce.chat.application.exception.ChatError;
import com.ecommerce.chat.application.exception.ChatException;
import com.ecommerce.chat.application.model.ChatModels.Actor;
import com.ecommerce.chat.application.model.ChatModels.AttachmentDownloadView;
import com.ecommerce.chat.application.model.ChatModels.AttachmentUploadView;
import com.ecommerce.chat.application.model.ChatModels.AttachmentView;
import com.ecommerce.chat.application.model.ChatModels.CreateAttachmentUploadCommand;
import com.ecommerce.chat.application.port.ChatAttachmentStorage;
import com.ecommerce.chat.infrastructure.persistence.entity.ChatAttachmentEntity;
import com.ecommerce.chat.infrastructure.persistence.entity.ChatAttachmentUploadEntity;
import com.ecommerce.chat.infrastructure.persistence.mapper.ChatAttachmentMapper;
import com.ecommerce.chat.infrastructure.persistence.mapper.ChatAttachmentUploadMapper;
import com.ecommerce.chat.infrastructure.persistence.mapper.ConversationMapper;
import com.ecommerce.chat.infrastructure.persistence.mapper.ConversationMemberMapper;
import com.ecommerce.chat.infrastructure.storage.ChatAttachmentStorageProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ChatAttachmentService {

    private static final Logger log = LoggerFactory.getLogger(ChatAttachmentService.class);
    private static final String PENDING = "PENDING";
    private static final String SCAN_PENDING = "SCAN_PENDING";
    private static final String SCANNING = "SCANNING";
    private static final String SCAN_RETRY = "SCAN_RETRY";
    private static final String SCAN_NEEDS_ATTENTION = "SCAN_NEEDS_ATTENTION";
    private static final String INFECTED = "INFECTED";
    private static final String READY = "READY";
    private static final String ATTACHED = "ATTACHED";
    private static final Set<String> IMAGE_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp");
    private static final Set<String> FILE_TYPES = Set.of(
            "application/pdf",
            "text/plain");
    private static final Map<String, Set<String>> EXTENSIONS = Map.of(
            "image/jpeg", Set.of("jpg", "jpeg"),
            "image/png", Set.of("png"),
            "image/webp", Set.of("webp"),
            "application/pdf", Set.of("pdf"),
            "text/plain", Set.of("txt"));
    private static final Map<String, String> OBJECT_EXTENSIONS = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp",
            "application/pdf", "pdf",
            "text/plain", "txt");

    private final ConversationMapper conversationMapper;
    private final ConversationMemberMapper memberMapper;
    private final ChatAttachmentUploadMapper uploadMapper;
    private final ChatAttachmentMapper attachmentMapper;
    private final ChatAttachmentStorage storage;
    private final ChatAttachmentStorageProperties properties;
    private final TransactionTemplate transactions;

    public ChatAttachmentService(
            ConversationMapper conversationMapper,
            ConversationMemberMapper memberMapper,
            ChatAttachmentUploadMapper uploadMapper,
            ChatAttachmentMapper attachmentMapper,
            ChatAttachmentStorage storage,
            ChatAttachmentStorageProperties properties,
            TransactionTemplate transactions) {
        this.conversationMapper = conversationMapper;
        this.memberMapper = memberMapper;
        this.uploadMapper = uploadMapper;
        this.attachmentMapper = attachmentMapper;
        this.storage = storage;
        this.properties = properties;
        this.transactions = transactions;
    }

    public AttachmentUploadView createUploadIntent(
            Actor actor,
            Long conversationId,
            CreateAttachmentUploadCommand command) {
        String fileName = normalizeFileName(command.fileName());
        String mimeType = normalizeMimeType(command.mimeType());
        validateDeclaredObject(fileName, mimeType, command.sizeBytes());
        String requestHash = hash(fileName, mimeType, Long.toString(command.sizeBytes()));

        ChatAttachmentUploadEntity upload = transactions.execute(status -> {
            requireConversationMember(conversationId, actor.userId());
            ChatAttachmentUploadEntity existing = uploadMapper.selectByClientUploadId(
                    conversationId,
                    actor.userId(),
                    command.clientUploadId());
            if (existing != null) {
                if (!requestHash.equals(existing.getRequestHash())) {
                    throw new ChatException(ChatError.IDEMPOTENCY_CONFLICT);
                }
                return existing;
            }

            Instant now = uploadMapper.currentTime();
            ChatAttachmentUploadEntity created = new ChatAttachmentUploadEntity();
            created.setId(IdWorker.getId());
            created.setConversationId(conversationId);
            created.setUploaderId(actor.userId());
            created.setClientUploadId(command.clientUploadId());
            created.setRequestHash(requestHash);
            created.setObjectKey(objectKey(
                    conversationId,
                    actor.userId(),
                    created.getId(),
                    mimeType));
            created.setOriginalFilename(fileName);
            created.setRequestedMimeType(mimeType);
            created.setRequestedSizeBytes(command.sizeBytes());
            created.setStatus(PENDING);
            created.setCleanupAttempts(0);
            created.setQuarantineCleanupAttempts(0);
            created.setScanAttempts(0);
            created.setExpiresAt(now.plus(properties.intentTtl()));
            created.setCreatedAt(now);
            created.setUpdatedAt(now);
            uploadMapper.insertIdempotent(created);
            ChatAttachmentUploadEntity persisted = uploadMapper.selectByClientUploadId(
                    conversationId,
                    actor.userId(),
                    command.clientUploadId());
            if (persisted == null || !requestHash.equals(persisted.getRequestHash())) {
                throw new ChatException(ChatError.IDEMPOTENCY_CONFLICT);
            }
            return persisted;
        });

        if (upload == null) {
            throw new IllegalStateException("Attachment upload transaction returned no result");
        }
        if (PENDING.equals(upload.getStatus())
                && !upload.getExpiresAt().isAfter(uploadMapper.currentTime())) {
            throw new ChatException(ChatError.ATTACHMENT_UPLOAD_EXPIRED);
        }
        String uploadUrl = PENDING.equals(upload.getStatus())
                ? storage.createUploadUrl(
                        properties.bucket(),
                        upload.getObjectKey(),
                        properties.uploadExpiry())
                : null;
        return toUploadView(upload, uploadUrl);
    }

    public AttachmentUploadView confirmUpload(Actor actor, Long conversationId, Long uploadId) {
        ChatAttachmentUploadEntity candidate = transactions.execute(status ->
                requireUploadForActor(actor, conversationId, uploadId, false));
        if (candidate == null) {
            throw new IllegalStateException("Attachment confirmation transaction returned no result");
        }
        if (isConfirmedStatus(candidate.getStatus())) {
            return toUploadView(candidate, null);
        }
        if (!PENDING.equals(candidate.getStatus())) {
            throw new ChatException(ChatError.ATTACHMENT_NOT_READY);
        }
        if (!candidate.getExpiresAt().isAfter(uploadMapper.currentTime())) {
            throw new ChatException(ChatError.ATTACHMENT_UPLOAD_EXPIRED);
        }

        ChatAttachmentStorage.StoredObject object = storage.inspect(
                properties.bucket(),
                candidate.getObjectKey(),
                properties.inspectionBytes(),
                properties.maximumSize().toBytes());
        String actualMimeType = normalizeMimeType(object.contentType());
        validateStoredObject(candidate, object, actualMimeType);
        if (object.entityTag() == null || object.entityTag().isBlank()) {
            throw new ChatException(ChatError.ATTACHMENT_OBJECT_MISMATCH);
        }

        String sourceObjectKey = candidate.getObjectKey();
        String sealedObjectKey = sealedObjectKey(candidate, actualMimeType, object.sha256());
        storage.copyIfUnchanged(
                properties.bucket(),
                sourceObjectKey,
                sealedObjectKey,
                object.entityTag());
        ChatAttachmentStorage.StoredObject sealedObject = storage.inspect(
                properties.bucket(),
                sealedObjectKey,
                properties.inspectionBytes(),
                properties.maximumSize().toBytes());
        String sealedMimeType = normalizeMimeType(sealedObject.contentType());
        validateStoredObject(candidate, sealedObject, sealedMimeType);
        if (!actualMimeType.equals(sealedMimeType)
                || object.sizeBytes() != sealedObject.sizeBytes()
                || !object.sha256().equals(sealedObject.sha256())) {
            storage.remove(properties.bucket(), sealedObjectKey);
            throw new ChatException(ChatError.ATTACHMENT_OBJECT_MISMATCH);
        }

        ChatAttachmentUploadEntity confirmed;
        try {
            confirmed = transactions.execute(status -> {
                ChatAttachmentUploadEntity locked = requireUploadForActor(
                        actor,
                        conversationId,
                        uploadId,
                        true);
                if (isConfirmedStatus(locked.getStatus())) {
                    return locked;
                }
                if (!PENDING.equals(locked.getStatus())) {
                    throw new ChatException(ChatError.ATTACHMENT_NOT_READY);
                }
                if (!locked.getExpiresAt().isAfter(uploadMapper.currentTime())) {
                    throw new ChatException(ChatError.ATTACHMENT_UPLOAD_EXPIRED);
                }
                Instant now = uploadMapper.currentTime();
                if (uploadMapper.markScanPending(
                        uploadId,
                        sourceObjectKey,
                        sealedObjectKey,
                        sealedMimeType,
                        sealedObject.sizeBytes(),
                        sealedObject.sha256(),
                        now) != 1) {
                    throw new ChatException(ChatError.ATTACHMENT_NOT_READY);
                }
                locked.setObjectKey(sealedObjectKey);
                locked.setQuarantineObjectKey(sourceObjectKey);
                locked.setQuarantineCleanupAttempts(0);
                locked.setQuarantineCleanupLastError(null);
                locked.setVerifiedMimeType(sealedMimeType);
                locked.setVerifiedSizeBytes(sealedObject.sizeBytes());
                locked.setVerifiedSha256(sealedObject.sha256());
                locked.setStatus(SCAN_PENDING);
                locked.setScanAttempts(0);
                locked.setUpdatedAt(now);
                return locked;
            });
        } catch (RuntimeException exception) {
            removeUnusedSealedObject(uploadId, sealedObjectKey, exception);
            throw exception;
        }
        if (confirmed == null) {
            removeUnusedSealedObject(uploadId, sealedObjectKey, null);
            throw new IllegalStateException("Attachment confirmation transaction returned no result");
        }
        if (!sealedObjectKey.equals(confirmed.getObjectKey())) {
            removeUnusedSealedObject(uploadId, sealedObjectKey, null);
        } else {
            cleanupQuarantineSource(confirmed, sourceObjectKey);
        }
        return toUploadView(confirmed, null);
    }

    private void cleanupQuarantineSource(
            ChatAttachmentUploadEntity upload,
            String sourceObjectKey) {
        int expectedAttempts = upload.getQuarantineCleanupAttempts();
        try {
            storage.remove(properties.bucket(), sourceObjectKey);
            transactions.executeWithoutResult(status ->
                    uploadMapper.markQuarantineCleaned(
                            upload.getId(),
                            sourceObjectKey,
                            expectedAttempts,
                            uploadMapper.currentTime()));
        } catch (RuntimeException exception) {
            recordQuarantineCleanupFailure(
                    upload.getId(),
                    sourceObjectKey,
                    expectedAttempts,
                    exception);
        }
    }

    private void recordQuarantineCleanupFailure(
            Long uploadId,
            String sourceObjectKey,
            int expectedAttempts,
            RuntimeException cleanupFailure) {
        String error = conciseError(cleanupFailure);
        try {
            transactions.executeWithoutResult(status ->
                    uploadMapper.markQuarantineCleanupFailed(
                            uploadId,
                            sourceObjectKey,
                            expectedAttempts,
                            error,
                            uploadMapper.currentTime()));
        } catch (RuntimeException persistenceFailure) {
            cleanupFailure.addSuppressed(persistenceFailure);
        }
        log.warn(
                "Quarantine chat attachment cleanup failed and remains retryable: "
                        + "uploadId={}, objectKey={}, error={}",
                uploadId,
                sourceObjectKey,
                error);
    }

    private void removeUnusedSealedObject(
            Long uploadId,
            String sealedObjectKey,
            RuntimeException confirmationFailure) {
        try {
            storage.remove(properties.bucket(), sealedObjectKey);
        } catch (RuntimeException cleanupFailure) {
            if (confirmationFailure != null) {
                confirmationFailure.addSuppressed(cleanupFailure);
            }
            log.warn(
                    "Unused sealed chat attachment could not be removed: uploadId={}, objectKey={}",
                    uploadId,
                    sealedObjectKey,
                    cleanupFailure);
        }
    }

    private String conciseError(RuntimeException exception) {
        String message = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    public AttachmentUploadView getUpload(Actor actor, Long conversationId, Long uploadId) {
        ChatAttachmentUploadEntity upload = transactions.execute(status ->
                requireUploadForActor(actor, conversationId, uploadId, false));
        if (upload == null) {
            throw new IllegalStateException("Attachment upload query returned no result");
        }
        return toUploadView(upload, null);
    }

    public AttachmentDownloadView createDownload(
            Actor actor,
            Long conversationId,
            Long messageId,
            Long attachmentId) {
        ChatAttachmentEntity attachment = transactions.execute(status -> {
            requireConversationMember(conversationId, actor.userId());
            ChatAttachmentEntity selected = attachmentMapper.selectInMessage(
                    conversationId,
                    messageId,
                    attachmentId);
            if (selected == null) {
                throw new ChatException(ChatError.ATTACHMENT_NOT_FOUND);
            }
            return selected;
        });
        if (attachment == null) {
            throw new IllegalStateException("Attachment download transaction returned no result");
        }
        ChatAttachmentStorage.StoredObject object = storage.inspect(
                properties.bucket(),
                attachment.getObjectKey(),
                properties.inspectionBytes(),
                properties.maximumSize().toBytes());
        String actualMimeType = normalizeMimeType(object.contentType());
        if (attachment.getSizeBytes() != object.sizeBytes()
                || !attachment.getMimeType().equals(actualMimeType)
                || !attachment.getSha256().equals(object.sha256())
                || !matchesFileHeader(actualMimeType, object.prefix())) {
            throw new ChatException(ChatError.ATTACHMENT_OBJECT_MISMATCH);
        }
        return new AttachmentDownloadView(
                attachment.getId(),
                storage.createDownloadUrl(
                        properties.bucket(),
                        attachment.getObjectKey(),
                        properties.downloadExpiry()),
                properties.downloadExpiry().toSeconds());
    }

    public List<ChatAttachmentUploadEntity> lockReadyUploads(
            Actor actor,
            Long conversationId,
            String messageType,
            List<Long> uploadIds) {
        List<Long> normalized = uploadIds == null ? List.of() : List.copyOf(uploadIds);
        if ("TEXT".equals(messageType)) {
            if (!normalized.isEmpty()) {
                throw new ChatException(ChatError.INVALID_ATTACHMENT);
            }
            return List.of();
        }
        if (!"IMAGE".equals(messageType) && !"FILE".equals(messageType)) {
            throw new ChatException(ChatError.INVALID_MESSAGE_TYPE);
        }
        if (normalized.isEmpty() || normalized.size() > 5
                || new HashSet<>(normalized).size() != normalized.size()) {
            throw new ChatException(ChatError.INVALID_ATTACHMENT);
        }

        List<ChatAttachmentUploadEntity> uploads = new ArrayList<>(normalized.size());
        for (Long uploadId : normalized) {
            if (uploadId == null || uploadId <= 0) {
                throw new ChatException(ChatError.INVALID_ATTACHMENT);
            }
            ChatAttachmentUploadEntity upload = requireUploadForActor(
                    actor,
                    conversationId,
                    uploadId,
                    true);
            if (ATTACHED.equals(upload.getStatus())) {
                throw new ChatException(ChatError.ATTACHMENT_ALREADY_ATTACHED);
            }
            if (INFECTED.equals(upload.getStatus())) {
                throw new ChatException(ChatError.ATTACHMENT_INFECTED);
            }
            if (!READY.equals(upload.getStatus())) {
                throw new ChatException(ChatError.ATTACHMENT_NOT_READY);
            }
            if ("IMAGE".equals(messageType) && !IMAGE_TYPES.contains(upload.getVerifiedMimeType())) {
                throw new ChatException(ChatError.INVALID_ATTACHMENT);
            }
            uploads.add(upload);
        }
        return uploads;
    }

    public List<AttachmentView> bindToMessage(
            Long messageId,
            List<ChatAttachmentUploadEntity> uploads,
            Instant now) {
        List<AttachmentView> views = new ArrayList<>(uploads.size());
        for (int index = 0; index < uploads.size(); index++) {
            ChatAttachmentUploadEntity upload = uploads.get(index);
            ChatAttachmentEntity attachment = new ChatAttachmentEntity();
            attachment.setId(IdWorker.getId());
            attachment.setMessageId(messageId);
            attachment.setUploadId(upload.getId());
            attachment.setObjectKey(upload.getObjectKey());
            attachment.setOriginalFilename(upload.getOriginalFilename());
            attachment.setMimeType(upload.getVerifiedMimeType());
            attachment.setSizeBytes(upload.getVerifiedSizeBytes());
            attachment.setSha256(upload.getVerifiedSha256());
            attachment.setSortOrder(index);
            attachment.setCreatedAt(now);
            attachmentMapper.insert(attachment);
            if (uploadMapper.markAttached(upload.getId(), messageId, now) != 1) {
                throw new ChatException(ChatError.ATTACHMENT_ALREADY_ATTACHED);
            }
            views.add(toAttachmentView(attachment));
        }
        return List.copyOf(views);
    }

    public List<AttachmentView> attachmentsForMessage(Long messageId) {
        return attachmentMapper.selectByMessageId(messageId).stream()
                .map(this::toAttachmentView)
                .toList();
    }

    public Map<Long, List<AttachmentView>> attachmentsForMessages(List<Long> messageIds) {
        if (messageIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<AttachmentView>> grouped = new LinkedHashMap<>();
        for (ChatAttachmentEntity attachment : attachmentMapper.selectByMessageIds(messageIds)) {
            grouped.computeIfAbsent(attachment.getMessageId(), ignored -> new ArrayList<>())
                    .add(toAttachmentView(attachment));
        }
        Map<Long, List<AttachmentView>> immutable = new HashMap<>();
        grouped.forEach((messageId, views) -> immutable.put(messageId, List.copyOf(views)));
        return Map.copyOf(immutable);
    }

    private ChatAttachmentUploadEntity requireUploadForActor(
            Actor actor,
            Long conversationId,
            Long uploadId,
            boolean forUpdate) {
        requireConversationMember(conversationId, actor.userId());
        ChatAttachmentUploadEntity upload = forUpdate
                ? uploadMapper.selectForUpdate(uploadId)
                : uploadMapper.selectById(uploadId);
        if (upload == null
                || !conversationId.equals(upload.getConversationId())
                || !actor.userId().equals(upload.getUploaderId())) {
            throw new ChatException(ChatError.ATTACHMENT_NOT_FOUND);
        }
        return upload;
    }

    private void requireConversationMember(Long conversationId, Long userId) {
        if (conversationMapper.selectById(conversationId) == null) {
            throw new ChatException(ChatError.CONVERSATION_NOT_FOUND);
        }
        if (memberMapper.selectMember(conversationId, userId) == null) {
            throw new ChatException(ChatError.CONVERSATION_ACCESS_DENIED);
        }
    }

    private void validateDeclaredObject(String fileName, String mimeType, long sizeBytes) {
        Set<String> extensions = EXTENSIONS.get(mimeType);
        if (extensions == null || sizeBytes <= 0 || sizeBytes > properties.maximumSize().toBytes()) {
            throw new ChatException(ChatError.INVALID_ATTACHMENT);
        }
        int separator = fileName.lastIndexOf('.');
        String extension = separator < 0
                ? ""
                : fileName.substring(separator + 1).toLowerCase(Locale.ROOT);
        if (!extensions.contains(extension)) {
            throw new ChatException(ChatError.INVALID_ATTACHMENT);
        }
    }

    private void validateStoredObject(
            ChatAttachmentUploadEntity upload,
            ChatAttachmentStorage.StoredObject object,
            String actualMimeType) {
        if (!upload.getRequestedMimeType().equals(actualMimeType)
                || upload.getRequestedSizeBytes() != object.sizeBytes()
                || object.sizeBytes() > properties.maximumSize().toBytes()
                || object.sha256() == null
                || !matchesFileHeader(actualMimeType, object.prefix())) {
            throw new ChatException(ChatError.ATTACHMENT_OBJECT_MISMATCH);
        }
    }

    private boolean matchesFileHeader(String mimeType, byte[] bytes) {
        return switch (mimeType) {
            case "image/jpeg" -> startsWith(bytes, 0xff, 0xd8, 0xff);
            case "image/png" -> startsWith(bytes, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a);
            case "image/webp" -> bytes.length >= 12
                    && startsWith(bytes, 0x52, 0x49, 0x46, 0x46)
                    && bytes[8] == 0x57
                    && bytes[9] == 0x45
                    && bytes[10] == 0x42
                    && bytes[11] == 0x50;
            case "application/pdf" -> startsWith(bytes, 0x25, 0x50, 0x44, 0x46, 0x2d);
            case "text/plain" -> bytes.length > 0
                    && Arrays.stream(toUnsigned(bytes))
                    .noneMatch(value -> value == 0);
            default -> false;
        };
    }

    private int[] toUnsigned(byte[] bytes) {
        int[] values = new int[bytes.length];
        for (int index = 0; index < bytes.length; index++) {
            values[index] = Byte.toUnsignedInt(bytes[index]);
        }
        return values;
    }

    private boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (Byte.toUnsignedInt(bytes[index]) != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private String normalizeFileName(String value) {
        if (value == null) {
            throw new ChatException(ChatError.INVALID_ATTACHMENT);
        }
        String normalized = value.trim();
        if (normalized.isEmpty()
                || normalized.length() > 255
                || normalized.contains("/")
                || normalized.contains("\\")
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new ChatException(ChatError.INVALID_ATTACHMENT);
        }
        return normalized;
    }

    private String normalizeMimeType(String value) {
        if (value == null || value.isBlank()) {
            throw new ChatException(ChatError.INVALID_ATTACHMENT);
        }
        return value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private String objectKey(Long conversationId, Long userId, Long uploadId, String mimeType) {
        return "quarantine/chat/%s/conversations/%d/users/%d/%d-%s.%s".formatted(
                properties.namespace(),
                conversationId,
                userId,
                uploadId,
                UUID.randomUUID().toString().replace("-", ""),
                OBJECT_EXTENSIONS.get(mimeType));
    }

    private String sealedObjectKey(
            ChatAttachmentUploadEntity upload,
            String mimeType,
            String sha256) {
        return "objects/chat/%s/conversations/%d/users/%d/%d-%s.%s".formatted(
                properties.namespace(),
                upload.getConversationId(),
                upload.getUploaderId(),
                upload.getId(),
                sha256,
                OBJECT_EXTENSIONS.get(mimeType));
    }

    private AttachmentUploadView toUploadView(
            ChatAttachmentUploadEntity upload,
            String uploadUrl) {
        return new AttachmentUploadView(
                upload.getId(),
                upload.getConversationId(),
                upload.getClientUploadId(),
                upload.getOriginalFilename(),
                upload.getRequestedMimeType(),
                upload.getRequestedSizeBytes(),
                upload.getStatus(),
                uploadUrl,
                upload.getExpiresAt(),
                upload.getScanAttempts(),
                upload.getScanEngine(),
                upload.getScanSignature(),
                upload.getScanCompletedAt());
    }

    private boolean isConfirmedStatus(String status) {
        return SCAN_PENDING.equals(status)
                || SCANNING.equals(status)
                || SCAN_RETRY.equals(status)
                || SCAN_NEEDS_ATTENTION.equals(status)
                || INFECTED.equals(status)
                || READY.equals(status)
                || ATTACHED.equals(status);
    }

    private AttachmentView toAttachmentView(ChatAttachmentEntity attachment) {
        return new AttachmentView(
                attachment.getId(),
                attachment.getOriginalFilename(),
                attachment.getMimeType(),
                attachment.getSizeBytes());
    }

    private String hash(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String normalized = String.join("\u001f", values);
            return HexFormat.of().formatHex(digest.digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
