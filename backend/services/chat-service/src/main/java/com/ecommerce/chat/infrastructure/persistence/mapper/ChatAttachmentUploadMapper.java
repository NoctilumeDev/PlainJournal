package com.ecommerce.chat.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.chat.infrastructure.persistence.entity.ChatAttachmentUploadEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

public interface ChatAttachmentUploadMapper extends BaseMapper<ChatAttachmentUploadEntity> {

    @Select("SELECT CURRENT_TIMESTAMP(3)")
    Instant currentTime();

    @Insert("""
            INSERT INTO chat_attachment_upload (
                id, conversation_id, uploader_id, client_upload_id, request_hash,
                object_key, quarantine_object_key, original_filename,
                requested_mime_type, requested_size_bytes,
                verified_mime_type, verified_size_bytes, verified_sha256, status, message_id,
                expires_at, cleanup_claimed_at, cleanup_attempts, cleanup_last_error,
                cleaned_at, quarantine_cleanup_attempts, quarantine_cleanup_last_error,
                quarantine_cleanup_claimed_at,
                scan_attempts, scan_claim_owner, scan_claimed_at,
                scan_claim_until, scan_engine, scan_signature, scan_last_error,
                scan_completed_at, created_at, updated_at
            ) VALUES (
                #{id}, #{conversationId}, #{uploaderId}, #{clientUploadId}, #{requestHash},
                #{objectKey}, #{quarantineObjectKey}, #{originalFilename},
                #{requestedMimeType}, #{requestedSizeBytes},
                #{verifiedMimeType}, #{verifiedSizeBytes}, #{verifiedSha256}, #{status}, #{messageId},
                #{expiresAt}, #{cleanupClaimedAt}, #{cleanupAttempts}, #{cleanupLastError},
                #{cleanedAt}, #{quarantineCleanupAttempts}, #{quarantineCleanupLastError},
                #{quarantineCleanupClaimedAt},
                #{scanAttempts}, #{scanClaimOwner}, #{scanClaimedAt},
                #{scanClaimUntil}, #{scanEngine}, #{scanSignature}, #{scanLastError},
                #{scanCompletedAt}, #{createdAt}, #{updatedAt}
            )
            ON DUPLICATE KEY UPDATE id = chat_attachment_upload.id
            """)
    int insertIdempotent(ChatAttachmentUploadEntity entity);

    @Select("""
            SELECT *
            FROM chat_attachment_upload
            WHERE conversation_id = #{conversationId}
              AND uploader_id = #{uploaderId}
              AND client_upload_id = #{clientUploadId}
            """)
    ChatAttachmentUploadEntity selectByClientUploadId(
            @Param("conversationId") Long conversationId,
            @Param("uploaderId") Long uploaderId,
            @Param("clientUploadId") String clientUploadId);

    @Select("""
            SELECT *
            FROM chat_attachment_upload
            WHERE id = #{id}
            FOR UPDATE
            """)
    ChatAttachmentUploadEntity selectForUpdate(@Param("id") Long id);

    @Update("""
            UPDATE chat_attachment_upload
            SET object_key = #{sealedObjectKey},
                quarantine_object_key = #{expectedSourceObjectKey},
                quarantine_cleanup_attempts = 0,
                quarantine_cleanup_last_error = NULL,
                quarantine_cleanup_claimed_at = NULL,
                verified_mime_type = #{mimeType},
                verified_size_bytes = #{sizeBytes},
                verified_sha256 = #{sha256},
                status = 'SCAN_PENDING',
                scan_attempts = 0,
                scan_claim_owner = NULL,
                scan_claimed_at = NULL,
                scan_claim_until = NULL,
                scan_engine = NULL,
                scan_signature = NULL,
                scan_last_error = NULL,
                scan_completed_at = NULL,
                updated_at = #{now}
            WHERE id = #{id}
              AND status = 'PENDING'
              AND object_key = #{expectedSourceObjectKey}
            """)
    int markScanPending(
            @Param("id") Long id,
            @Param("expectedSourceObjectKey") String expectedSourceObjectKey,
            @Param("sealedObjectKey") String sealedObjectKey,
            @Param("mimeType") String mimeType,
            @Param("sizeBytes") long sizeBytes,
            @Param("sha256") String sha256,
            @Param("now") Instant now);

    @Select("""
            SELECT *
            FROM chat_attachment_upload
            WHERE quarantine_object_key IS NOT NULL
              AND (
                    quarantine_cleanup_claimed_at IS NULL
                    OR quarantine_cleanup_claimed_at <= #{recoverBefore}
                  )
            ORDER BY updated_at, id
            LIMIT #{limit}
            """)
    List<ChatAttachmentUploadEntity> selectQuarantineCleanupCandidates(
            @Param("recoverBefore") Instant recoverBefore,
            @Param("limit") int limit);

    @Update("""
            UPDATE chat_attachment_upload
            SET quarantine_cleanup_claimed_at = #{claimedAt},
                quarantine_cleanup_attempts = quarantine_cleanup_attempts + 1,
                quarantine_cleanup_last_error = NULL,
                updated_at = #{claimedAt}
            WHERE id = #{id}
              AND quarantine_object_key = #{expectedObjectKey}
              AND quarantine_cleanup_attempts = #{expectedAttempts}
              AND (
                    quarantine_cleanup_claimed_at IS NULL
                    OR quarantine_cleanup_claimed_at <= #{recoverBefore}
                  )
            """)
    int claimQuarantineCleanup(
            @Param("id") Long id,
            @Param("expectedObjectKey") String expectedObjectKey,
            @Param("expectedAttempts") int expectedAttempts,
            @Param("claimedAt") Instant claimedAt,
            @Param("recoverBefore") Instant recoverBefore);

    @Update("""
            UPDATE chat_attachment_upload
            SET quarantine_object_key = NULL,
                quarantine_cleanup_attempts = quarantine_cleanup_attempts + 1,
                quarantine_cleanup_last_error = NULL,
                quarantine_cleanup_claimed_at = NULL,
                updated_at = #{now}
            WHERE id = #{id}
              AND quarantine_object_key = #{expectedObjectKey}
              AND quarantine_cleanup_attempts = #{expectedAttempts}
              AND quarantine_cleanup_claimed_at IS NULL
            """)
    int markQuarantineCleaned(
            @Param("id") Long id,
            @Param("expectedObjectKey") String expectedObjectKey,
            @Param("expectedAttempts") int expectedAttempts,
            @Param("now") Instant now);

    @Update("""
            UPDATE chat_attachment_upload
            SET quarantine_cleanup_attempts = quarantine_cleanup_attempts + 1,
                quarantine_cleanup_last_error = #{error},
                quarantine_cleanup_claimed_at = NULL,
                updated_at = #{now}
            WHERE id = #{id}
              AND quarantine_object_key = #{expectedObjectKey}
              AND quarantine_cleanup_attempts = #{expectedAttempts}
              AND quarantine_cleanup_claimed_at IS NULL
            """)
    int markQuarantineCleanupFailed(
            @Param("id") Long id,
            @Param("expectedObjectKey") String expectedObjectKey,
            @Param("expectedAttempts") int expectedAttempts,
            @Param("error") String error,
            @Param("now") Instant now);

    @Update("""
            UPDATE chat_attachment_upload
            SET quarantine_object_key = NULL,
                quarantine_cleanup_last_error = NULL,
                quarantine_cleanup_claimed_at = NULL,
                updated_at = #{now}
            WHERE id = #{id}
              AND quarantine_object_key = #{expectedObjectKey}
              AND quarantine_cleanup_attempts = #{claimedAttempt}
              AND quarantine_cleanup_claimed_at IS NOT NULL
            """)
    int markClaimedQuarantineCleaned(
            @Param("id") Long id,
            @Param("expectedObjectKey") String expectedObjectKey,
            @Param("claimedAttempt") int claimedAttempt,
            @Param("now") Instant now);

    @Update("""
            UPDATE chat_attachment_upload
            SET quarantine_cleanup_last_error = #{error},
                quarantine_cleanup_claimed_at = NULL,
                updated_at = #{now}
            WHERE id = #{id}
              AND quarantine_object_key = #{expectedObjectKey}
              AND quarantine_cleanup_attempts = #{claimedAttempt}
              AND quarantine_cleanup_claimed_at IS NOT NULL
            """)
    int markClaimedQuarantineCleanupFailed(
            @Param("id") Long id,
            @Param("expectedObjectKey") String expectedObjectKey,
            @Param("claimedAttempt") int claimedAttempt,
            @Param("error") String error,
            @Param("now") Instant now);

    @Select("""
            SELECT *
            FROM chat_attachment_upload
            WHERE expires_at > #{now}
              AND (
                    status IN ('SCAN_PENDING', 'SCAN_RETRY')
                    OR (
                      status = 'SCANNING'
                      AND scan_claim_until <= #{now}
                    )
                  )
            ORDER BY updated_at, id
            LIMIT #{limit}
            """)
    List<ChatAttachmentUploadEntity> selectScanCandidates(
            @Param("now") Instant now,
            @Param("limit") int limit);

    @Update("""
            UPDATE chat_attachment_upload
            SET status = 'SCANNING',
                scan_attempts = scan_attempts + 1,
                scan_claim_owner = #{claimOwner},
                scan_claimed_at = #{now},
                scan_claim_until = #{claimUntil},
                scan_last_error = NULL,
                updated_at = #{now}
            WHERE id = #{id}
              AND expires_at > #{now}
              AND scan_attempts = #{expectedAttempts}
              AND (
                    status IN ('SCAN_PENDING', 'SCAN_RETRY')
                    OR (
                      status = 'SCANNING'
                      AND scan_claim_until <= #{now}
                    )
                  )
            """)
    int claimScan(
            @Param("id") Long id,
            @Param("claimOwner") String claimOwner,
            @Param("expectedAttempts") int expectedAttempts,
            @Param("now") Instant now,
            @Param("claimUntil") Instant claimUntil);

    @Update("""
            UPDATE chat_attachment_upload
            SET status = 'READY',
                scan_engine = #{engine},
                scan_signature = NULL,
                scan_last_error = NULL,
                scan_completed_at = #{now},
                scan_claim_owner = NULL,
                scan_claimed_at = NULL,
                scan_claim_until = NULL,
                updated_at = #{now}
            WHERE id = #{id}
              AND status = 'SCANNING'
              AND scan_claim_owner = #{claimOwner}
              AND scan_attempts = #{expectedAttempts}
              AND scan_claim_until > #{now}
            """)
    int markScanReady(
            @Param("id") Long id,
            @Param("claimOwner") String claimOwner,
            @Param("expectedAttempts") int expectedAttempts,
            @Param("engine") String engine,
            @Param("now") Instant now);

    @Update("""
            UPDATE chat_attachment_upload
            SET status = 'INFECTED',
                scan_engine = #{engine},
                scan_signature = #{signature},
                scan_last_error = NULL,
                scan_completed_at = #{now},
                scan_claim_owner = NULL,
                scan_claimed_at = NULL,
                scan_claim_until = NULL,
                updated_at = #{now}
            WHERE id = #{id}
              AND status = 'SCANNING'
              AND scan_claim_owner = #{claimOwner}
              AND scan_attempts = #{expectedAttempts}
              AND scan_claim_until > #{now}
            """)
    int markScanInfected(
            @Param("id") Long id,
            @Param("claimOwner") String claimOwner,
            @Param("expectedAttempts") int expectedAttempts,
            @Param("engine") String engine,
            @Param("signature") String signature,
            @Param("now") Instant now);

    @Update("""
            UPDATE chat_attachment_upload
            SET status = #{targetStatus},
                scan_last_error = #{error},
                scan_claim_owner = NULL,
                scan_claimed_at = NULL,
                scan_claim_until = NULL,
                updated_at = #{now}
            WHERE id = #{id}
              AND status = 'SCANNING'
              AND scan_claim_owner = #{claimOwner}
              AND scan_attempts = #{expectedAttempts}
              AND scan_claim_until > #{now}
            """)
    int markScanFailure(
            @Param("id") Long id,
            @Param("claimOwner") String claimOwner,
            @Param("expectedAttempts") int expectedAttempts,
            @Param("targetStatus") String targetStatus,
            @Param("error") String error,
            @Param("now") Instant now);

    @Update("""
            UPDATE chat_attachment_upload
            SET status = 'SCAN_PENDING',
                scan_attempts = 0,
                scan_claim_owner = NULL,
                scan_claimed_at = NULL,
                scan_claim_until = NULL,
                scan_engine = NULL,
                scan_signature = NULL,
                scan_last_error = NULL,
                scan_completed_at = NULL,
                updated_at = #{now}
            WHERE id = #{id}
              AND status = 'SCAN_NEEDS_ATTENTION'
            """)
    int resetScanForRetry(
            @Param("id") Long id,
            @Param("now") Instant now);

    @Update("""
            UPDATE chat_attachment_upload
            SET status = 'ATTACHED',
                message_id = #{messageId},
                updated_at = #{now}
            WHERE id = #{id}
              AND status = 'READY'
            """)
    int markAttached(
            @Param("id") Long id,
            @Param("messageId") Long messageId,
            @Param("now") Instant now);

    @Select("""
            SELECT *
            FROM chat_attachment_upload
            WHERE (
                    status IN (
                      'PENDING', 'SCAN_PENDING', 'SCAN_RETRY',
                      'SCAN_NEEDS_ATTENTION', 'INFECTED', 'READY', 'CLEANUP_PENDING'
                    )
                    AND expires_at <= #{now}
                  )
               OR (
                    status = 'CLEANING'
                    AND cleanup_claimed_at <= #{recoverBefore}
                  )
               OR (
                    status = 'SCANNING'
                    AND expires_at <= #{now}
                    AND scan_claim_until <= #{now}
                  )
            ORDER BY expires_at, id
            LIMIT #{limit}
            """)
    List<ChatAttachmentUploadEntity> selectCleanupCandidates(
            @Param("now") Instant now,
            @Param("recoverBefore") Instant recoverBefore,
            @Param("limit") int limit);

    @Update("""
            UPDATE chat_attachment_upload
            SET status = 'CLEANING',
                cleanup_claimed_at = #{now},
                cleanup_attempts = cleanup_attempts + 1,
                cleanup_last_error = NULL,
                updated_at = #{now}
            WHERE id = #{id}
              AND cleanup_attempts = #{expectedAttempts}
              AND (
                    (
                      status IN (
                        'PENDING', 'SCAN_PENDING', 'SCAN_RETRY',
                        'SCAN_NEEDS_ATTENTION', 'INFECTED', 'READY', 'CLEANUP_PENDING'
                      )
                      AND expires_at <= #{now}
                    )
                    OR (
                      status = 'CLEANING'
                      AND cleanup_claimed_at <= #{recoverBefore}
                    )
                    OR (
                      status = 'SCANNING'
                      AND expires_at <= #{now}
                      AND scan_claim_until <= #{now}
                    )
                  )
            """)
    int claimCleanup(
            @Param("id") Long id,
            @Param("expectedAttempts") int expectedAttempts,
            @Param("now") Instant now,
            @Param("recoverBefore") Instant recoverBefore);

    @Update("""
            UPDATE chat_attachment_upload
            SET status = 'DELETED',
                cleaned_at = #{now},
                cleanup_last_error = NULL,
                updated_at = #{now}
            WHERE id = #{id}
              AND status = 'CLEANING'
              AND cleanup_attempts = #{expectedAttempts}
            """)
    int markDeleted(
            @Param("id") Long id,
            @Param("expectedAttempts") int expectedAttempts,
            @Param("now") Instant now);

    @Update("""
            UPDATE chat_attachment_upload
            SET status = 'CLEANUP_PENDING',
                cleanup_last_error = #{error},
                updated_at = #{now}
            WHERE id = #{id}
              AND status = 'CLEANING'
              AND cleanup_attempts = #{expectedAttempts}
            """)
    int markCleanupPending(
            @Param("id") Long id,
            @Param("expectedAttempts") int expectedAttempts,
            @Param("error") String error,
            @Param("now") Instant now);
}
