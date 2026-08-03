package com.ecommerce.chat.application.service;

import com.ecommerce.chat.application.port.ChatAttachmentStorage;
import com.ecommerce.chat.infrastructure.persistence.entity.ChatAttachmentUploadEntity;
import com.ecommerce.chat.infrastructure.persistence.mapper.ChatAttachmentUploadMapper;
import com.ecommerce.chat.infrastructure.storage.ChatAttachmentStorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

@Service
public class ChatAttachmentCleanupService {

    private static final Logger log = LoggerFactory.getLogger(ChatAttachmentCleanupService.class);

    private final ChatAttachmentUploadMapper uploadMapper;
    private final ChatAttachmentStorage storage;
    private final ChatAttachmentStorageProperties properties;
    private final TransactionTemplate transactions;

    public ChatAttachmentCleanupService(
            ChatAttachmentUploadMapper uploadMapper,
            ChatAttachmentStorage storage,
            ChatAttachmentStorageProperties properties,
            TransactionTemplate transactions) {
        this.uploadMapper = uploadMapper;
        this.storage = storage;
        this.properties = properties;
        this.transactions = transactions;
    }

    public int cleanupBatch() {
        Instant now = uploadMapper.currentTime();
        Instant recoverBefore = now.minus(properties.cleanupRecoveryAge());
        List<ChatAttachmentUploadEntity> candidates = uploadMapper.selectCleanupCandidates(
                now,
                recoverBefore,
                properties.cleanupBatchSize());
        int cleaned = 0;
        for (ChatAttachmentUploadEntity candidate : candidates) {
            int claimedAttempt = candidate.getCleanupAttempts() + 1;
            Boolean claimed = transactions.execute(status ->
                    uploadMapper.claimCleanup(
                            candidate.getId(),
                            candidate.getCleanupAttempts(),
                            uploadMapper.currentTime(),
                            recoverBefore) == 1);
            if (!Boolean.TRUE.equals(claimed)) {
                continue;
            }
            try {
                storage.remove(properties.bucket(), candidate.getObjectKey());
                Boolean deleted = transactions.execute(status ->
                        uploadMapper.markDeleted(
                                candidate.getId(),
                                claimedAttempt,
                                uploadMapper.currentTime()) == 1);
                if (!Boolean.TRUE.equals(deleted)) {
                    throw new IllegalStateException(
                            "Attachment cleanup state changed before completion");
                }
                cleaned++;
            } catch (RuntimeException exception) {
                String error = conciseError(exception);
                transactions.executeWithoutResult(status ->
                        uploadMapper.markCleanupPending(
                                candidate.getId(),
                                claimedAttempt,
                                error,
                                uploadMapper.currentTime()));
                log.warn("Expired chat attachment cleanup failed and remains retryable: uploadId={}, error={}",
                        candidate.getId(), error);
            }
        }
        return cleaned;
    }

    public int cleanupQuarantineBatch() {
        Instant now = uploadMapper.currentTime();
        Instant recoverBefore = now.minus(properties.cleanupRecoveryAge());
        List<ChatAttachmentUploadEntity> candidates =
                uploadMapper.selectQuarantineCleanupCandidates(
                        recoverBefore,
                        properties.cleanupBatchSize());
        int cleaned = 0;
        for (ChatAttachmentUploadEntity candidate : candidates) {
            String objectKey = candidate.getQuarantineObjectKey();
            int claimedAttempt = candidate.getQuarantineCleanupAttempts() + 1;
            Instant claimedAt = uploadMapper.currentTime();
            Boolean claimed = transactions.execute(status ->
                    uploadMapper.claimQuarantineCleanup(
                            candidate.getId(),
                            objectKey,
                            candidate.getQuarantineCleanupAttempts(),
                            claimedAt,
                            recoverBefore) == 1);
            if (!Boolean.TRUE.equals(claimed)) {
                continue;
            }
            try {
                storage.remove(properties.bucket(), objectKey);
                Boolean updated = transactions.execute(status ->
                        uploadMapper.markClaimedQuarantineCleaned(
                                candidate.getId(),
                                objectKey,
                                claimedAttempt,
                                uploadMapper.currentTime()) == 1);
                if (Boolean.TRUE.equals(updated)) {
                    cleaned++;
                }
            } catch (RuntimeException exception) {
                String error = conciseError(exception);
                transactions.executeWithoutResult(status ->
                        uploadMapper.markClaimedQuarantineCleanupFailed(
                                candidate.getId(),
                                objectKey,
                                claimedAttempt,
                                error,
                                uploadMapper.currentTime()));
                log.warn(
                        "Quarantine chat attachment cleanup failed and remains retryable: "
                                + "uploadId={}, error={}",
                        candidate.getId(),
                        error);
            }
        }
        return cleaned;
    }

    private String conciseError(RuntimeException exception) {
        String message = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
