package com.ecommerce.chat.application.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ecommerce.chat.application.exception.ChatError;
import com.ecommerce.chat.application.exception.ChatException;
import com.ecommerce.chat.application.model.ChatModels.Actor;
import com.ecommerce.chat.application.model.ChatModels.AttachmentScanRetryAuditView;
import com.ecommerce.chat.application.model.ChatModels.AttachmentUploadView;
import com.ecommerce.chat.application.model.ChatModels.RetryAttachmentScanCommand;
import com.ecommerce.chat.application.port.ChatAttachmentMalwareScanner;
import com.ecommerce.chat.application.port.ChatAttachmentStorage;
import com.ecommerce.chat.infrastructure.persistence.entity.ChatAttachmentScanRetryAuditEntity;
import com.ecommerce.chat.infrastructure.persistence.entity.ChatAttachmentUploadEntity;
import com.ecommerce.chat.infrastructure.persistence.mapper.ChatAttachmentScanRetryAuditMapper;
import com.ecommerce.chat.infrastructure.persistence.mapper.ChatAttachmentUploadMapper;
import com.ecommerce.chat.infrastructure.storage.ChatAttachmentScanProperties;
import com.ecommerce.chat.infrastructure.storage.ChatAttachmentStorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

@Service
public class ChatAttachmentScanService {

    private static final Logger log = LoggerFactory.getLogger(ChatAttachmentScanService.class);
    private static final String SCAN_NEEDS_ATTENTION = "SCAN_NEEDS_ATTENTION";
    private static final String SCAN_PENDING = "SCAN_PENDING";

    private final ChatAttachmentUploadMapper uploadMapper;
    private final ChatAttachmentScanRetryAuditMapper auditMapper;
    private final ChatAttachmentStorage storage;
    private final ChatAttachmentMalwareScanner scanner;
    private final ChatAttachmentStorageProperties storageProperties;
    private final ChatAttachmentScanProperties scanProperties;
    private final TransactionTemplate transactions;

    public ChatAttachmentScanService(
            ChatAttachmentUploadMapper uploadMapper,
            ChatAttachmentScanRetryAuditMapper auditMapper,
            ChatAttachmentStorage storage,
            ChatAttachmentMalwareScanner scanner,
            ChatAttachmentStorageProperties storageProperties,
            ChatAttachmentScanProperties scanProperties,
            TransactionTemplate transactions) {
        this.uploadMapper = uploadMapper;
        this.auditMapper = auditMapper;
        this.storage = storage;
        this.scanner = scanner;
        this.storageProperties = storageProperties;
        this.scanProperties = scanProperties;
        this.transactions = transactions;
    }

    public int scanBatch() {
        Instant now = uploadMapper.currentTime();
        List<ChatAttachmentUploadEntity> candidates = uploadMapper.selectScanCandidates(
                now,
                scanProperties.batchSize());
        int processed = 0;
        for (ChatAttachmentUploadEntity candidate : candidates) {
            ChatAttachmentUploadEntity claimed = transactions.execute(status -> {
                Instant claimedAt = uploadMapper.currentTime();
                if (uploadMapper.claimScan(
                        candidate.getId(),
                        scanProperties.scannerId(),
                        candidate.getScanAttempts(),
                        claimedAt,
                        claimedAt.plus(scanProperties.leaseDuration())) != 1) {
                    return null;
                }
                return uploadMapper.selectById(candidate.getId());
            });
            if (claimed == null) {
                continue;
            }
            processClaimed(claimed);
            processed++;
        }
        return processed;
    }

    public AttachmentUploadView retryScan(Actor actor, RetryAttachmentScanCommand command) {
        requireSupportAgent(actor);
        String reason = command.reason().strip();
        String requestHash = hash(
                command.uploadId().toString(),
                command.operatorId().toString(),
                reason);
        RetryResult result = transactions.execute(status -> retryWithinTransaction(
                command,
                reason,
                requestHash));
        if (result == null) {
            throw new IllegalStateException("Attachment scan retry transaction returned no result");
        }
        if (result.error() != null) {
            throw new ChatException(result.error());
        }
        return toUploadView(result.upload());
    }

    public List<AttachmentScanRetryAuditView> listRetryAudits(
            Actor actor,
            Long uploadId,
            int limit) {
        requireSupportAgent(actor);
        if (uploadMapper.selectById(uploadId) == null) {
            throw new ChatException(ChatError.ATTACHMENT_NOT_FOUND);
        }
        return auditMapper.selectByUploadId(uploadId, limit).stream()
                .map(this::toAuditView)
                .toList();
    }

    private void processClaimed(ChatAttachmentUploadEntity upload) {
        try (InputStream content = storage.open(
                storageProperties.bucket(),
                upload.getObjectKey())) {
            ChatAttachmentMalwareScanner.ScanResult result = scanner.scan(
                    content,
                    storageProperties.maximumSize().toBytes());
            requireScannedObjectMatch(upload, result);
            Instant completedAt = uploadMapper.currentTime();
            if (result.verdict() == ChatAttachmentMalwareScanner.Verdict.CLEAN) {
                completeReady(upload, result, completedAt);
            } else {
                completeInfected(upload, result, completedAt);
            }
        } catch (IOException | RuntimeException exception) {
            recordFailure(upload, exception);
        }
    }

    private void completeReady(
            ChatAttachmentUploadEntity upload,
            ChatAttachmentMalwareScanner.ScanResult result,
            Instant completedAt) {
        Boolean updated = transactions.execute(status ->
                uploadMapper.markScanReady(
                        upload.getId(),
                        scanProperties.scannerId(),
                        upload.getScanAttempts(),
                        result.engine(),
                        completedAt) == 1);
        if (!Boolean.TRUE.equals(updated)) {
            log.warn("Chat attachment clean scan lost its lease: uploadId={}", upload.getId());
        }
    }

    private void completeInfected(
            ChatAttachmentUploadEntity upload,
            ChatAttachmentMalwareScanner.ScanResult result,
            Instant completedAt) {
        String signature = truncate(result.signature(), 255);
        Boolean updated = transactions.execute(status ->
                uploadMapper.markScanInfected(
                        upload.getId(),
                        scanProperties.scannerId(),
                        upload.getScanAttempts(),
                        result.engine(),
                        signature,
                        completedAt) == 1);
        if (!Boolean.TRUE.equals(updated)) {
            log.warn("Chat attachment infected scan lost its lease: uploadId={}", upload.getId());
        }
    }

    private void recordFailure(ChatAttachmentUploadEntity upload, Exception exception) {
        String targetStatus = upload.getScanAttempts() >= scanProperties.maximumAttempts()
                ? SCAN_NEEDS_ATTENTION
                : "SCAN_RETRY";
        String error = conciseError(exception);
        Boolean updated = transactions.execute(status ->
                uploadMapper.markScanFailure(
                        upload.getId(),
                        scanProperties.scannerId(),
                        upload.getScanAttempts(),
                        targetStatus,
                        error,
                        uploadMapper.currentTime()) == 1);
        if (Boolean.TRUE.equals(updated)) {
            log.warn("Chat attachment scan failed: uploadId={}, status={}, attempts={}, error={}",
                    upload.getId(), targetStatus, upload.getScanAttempts(), error);
        }
    }

    private RetryResult retryWithinTransaction(
            RetryAttachmentScanCommand command,
            String reason,
            String requestHash) {
        ChatAttachmentScanRetryAuditEntity existing =
                auditMapper.selectByCommandIdForUpdate(command.commandId());
        if (existing != null) {
            requireSameRequest(existing, requestHash);
            return resultFromAudit(existing);
        }
        ChatAttachmentUploadEntity upload = uploadMapper.selectForUpdate(command.uploadId());
        if (upload == null) {
            throw new ChatException(ChatError.ATTACHMENT_NOT_FOUND);
        }
        existing = auditMapper.selectByCommandIdForUpdate(command.commandId());
        if (existing != null) {
            requireSameRequest(existing, requestHash);
            return resultFromAudit(existing);
        }

        Instant now = uploadMapper.currentTime();
        if (!SCAN_NEEDS_ATTENTION.equals(upload.getStatus())) {
            ChatAttachmentScanRetryAuditEntity audit = audit(
                    command,
                    reason,
                    requestHash,
                    upload,
                    "REJECTED",
                    ChatError.ATTACHMENT_SCAN_RETRY_NOT_ALLOWED.code(),
                    upload.getStatus(),
                    upload.getScanAttempts(),
                    now);
            persistAudit(audit, requestHash);
            return new RetryResult(null, ChatError.ATTACHMENT_SCAN_RETRY_NOT_ALLOWED);
        }
        if (uploadMapper.resetScanForRetry(upload.getId(), now) != 1) {
            throw new ChatException(ChatError.ATTACHMENT_SCAN_RETRY_NOT_ALLOWED);
        }
        ChatAttachmentScanRetryAuditEntity audit = audit(
                command,
                reason,
                requestHash,
                upload,
                "ACCEPTED",
                null,
                SCAN_PENDING,
                0,
                now);
        persistAudit(audit, requestHash);
        ChatAttachmentUploadEntity reset = uploadMapper.selectForUpdate(upload.getId());
        return new RetryResult(reset, null);
    }

    private ChatAttachmentScanRetryAuditEntity audit(
            RetryAttachmentScanCommand command,
            String reason,
            String requestHash,
            ChatAttachmentUploadEntity upload,
            String outcome,
            String errorCode,
            String afterStatus,
            Integer afterAttempts,
            Instant now) {
        ChatAttachmentScanRetryAuditEntity audit = new ChatAttachmentScanRetryAuditEntity();
        audit.setId(IdWorker.getId());
        audit.setCommandId(command.commandId());
        audit.setRequestHash(requestHash);
        audit.setUploadId(command.uploadId());
        audit.setOperatorId(command.operatorId());
        audit.setReason(reason);
        audit.setBeforeStatus(upload.getStatus());
        audit.setBeforeAttempts(upload.getScanAttempts());
        audit.setBeforeLastError(upload.getScanLastError());
        audit.setOutcome(outcome);
        audit.setErrorCode(errorCode);
        audit.setAfterStatus(afterStatus);
        audit.setAfterAttempts(afterAttempts);
        audit.setCreatedAt(now);
        return audit;
    }

    private ChatAttachmentScanRetryAuditEntity persistAudit(
            ChatAttachmentScanRetryAuditEntity audit,
            String requestHash) {
        auditMapper.insertIdempotent(audit);
        ChatAttachmentScanRetryAuditEntity persisted =
                auditMapper.selectByCommandIdForUpdate(audit.getCommandId());
        requireSameRequest(persisted, requestHash);
        return persisted;
    }

    private RetryResult resultFromAudit(ChatAttachmentScanRetryAuditEntity audit) {
        if ("REJECTED".equals(audit.getOutcome())) {
            return new RetryResult(null, ChatError.valueOf(audit.getErrorCode()));
        }
        return new RetryResult(uploadMapper.selectById(audit.getUploadId()), null);
    }

    private void requireSameRequest(
            ChatAttachmentScanRetryAuditEntity audit,
            String requestHash) {
        if (audit == null || !constantEquals(audit.getRequestHash(), requestHash)) {
            throw new ChatException(ChatError.IDEMPOTENCY_CONFLICT);
        }
    }

    private void requireScannedObjectMatch(
            ChatAttachmentUploadEntity upload,
            ChatAttachmentMalwareScanner.ScanResult result) {
        if (upload.getVerifiedSizeBytes() == null
                || upload.getVerifiedSha256() == null
                || upload.getVerifiedSizeBytes() != result.sizeBytes()
                || !constantEquals(upload.getVerifiedSha256(), result.sha256())) {
            throw new IllegalStateException(
                    "Attachment object changed between confirmation and malware scan");
        }
    }

    private void requireSupportAgent(Actor actor) {
        if (!actor.supportAgent()) {
            throw new ChatException(ChatError.CONVERSATION_ACCESS_DENIED);
        }
    }

    private AttachmentUploadView toUploadView(ChatAttachmentUploadEntity upload) {
        return new AttachmentUploadView(
                upload.getId(),
                upload.getConversationId(),
                upload.getClientUploadId(),
                upload.getOriginalFilename(),
                upload.getRequestedMimeType(),
                upload.getRequestedSizeBytes(),
                upload.getStatus(),
                null,
                upload.getExpiresAt(),
                upload.getScanAttempts(),
                upload.getScanEngine(),
                upload.getScanSignature(),
                upload.getScanCompletedAt());
    }

    private AttachmentScanRetryAuditView toAuditView(
            ChatAttachmentScanRetryAuditEntity audit) {
        return new AttachmentScanRetryAuditView(
                audit.getCommandId(),
                audit.getUploadId(),
                audit.getOperatorId(),
                audit.getReason(),
                audit.getBeforeStatus(),
                audit.getBeforeAttempts(),
                audit.getBeforeLastError(),
                audit.getOutcome(),
                audit.getErrorCode(),
                audit.getAfterStatus(),
                audit.getAfterAttempts(),
                audit.getCreatedAt());
    }

    private String conciseError(Exception exception) {
        String message = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        return truncate(message, 500);
    }

    private String truncate(String value, int maximumLength) {
        if (value == null || value.length() <= maximumLength) {
            return value;
        }
        return value.substring(0, maximumLength);
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

    private boolean constantEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private record RetryResult(ChatAttachmentUploadEntity upload, ChatError error) {
    }
}
