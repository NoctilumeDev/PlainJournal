package com.ecommerce.chat.application.port;

import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

public interface ChatAttachmentStorage {

    String createUploadUrl(String bucket, String objectKey, Duration expiry);

    String createDownloadUrl(String bucket, String objectKey, Duration expiry);

    void copyIfUnchanged(
            String bucket,
            String sourceObjectKey,
            String targetObjectKey,
            String expectedEntityTag);

    StoredObject inspect(
            String bucket,
            String objectKey,
            int prefixLength,
            long maximumBytes);

    InputStream open(String bucket, String objectKey);

    void remove(String bucket, String objectKey);

    record StoredObject(
            long sizeBytes,
            String contentType,
            byte[] prefix,
            String sha256,
            String entityTag) {
        public StoredObject(long sizeBytes, String contentType, byte[] content) {
            this(sizeBytes, contentType, content, sha256(content), sha256(content));
        }

        public StoredObject {
            prefix = prefix.clone();
        }

        @Override
        public byte[] prefix() {
            return prefix.clone();
        }

        private static String sha256(byte[] content) {
            try {
                return HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(content));
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is unavailable", exception);
            }
        }
    }
}
