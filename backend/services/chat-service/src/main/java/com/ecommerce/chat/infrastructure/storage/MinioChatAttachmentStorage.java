package com.ecommerce.chat.infrastructure.storage;

import com.ecommerce.chat.application.exception.ChatError;
import com.ecommerce.chat.application.exception.ChatException;
import com.ecommerce.chat.application.port.ChatAttachmentStorage;
import io.minio.CopyObjectArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.Http.Method;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.SourceObject;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

@Component
public class MinioChatAttachmentStorage implements ChatAttachmentStorage {

    private final MinioClient minioClient;

    public MinioChatAttachmentStorage(MinioClient minioClient) {
        this.minioClient = minioClient;
    }

    @Override
    public String createUploadUrl(String bucket, String objectKey, Duration expiry) {
        return presignedUrl(Method.PUT, bucket, objectKey, expiry);
    }

    @Override
    public String createDownloadUrl(String bucket, String objectKey, Duration expiry) {
        return presignedUrl(Method.GET, bucket, objectKey, expiry);
    }

    @Override
    public void copyIfUnchanged(
            String bucket,
            String sourceObjectKey,
            String targetObjectKey,
            String expectedEntityTag) {
        try {
            minioClient.copyObject(CopyObjectArgs.builder()
                    .bucket(bucket)
                    .object(targetObjectKey)
                    .source(SourceObject.builder()
                            .bucket(bucket)
                            .object(sourceObjectKey)
                            .matchETag(expectedEntityTag)
                            .build())
                    .build());
        } catch (ErrorResponseException exception) {
            throw storageException(exception);
        } catch (Exception exception) {
            throw new ChatException(ChatError.ATTACHMENT_STORAGE_UNAVAILABLE, exception);
        }
    }

    @Override
    public StoredObject inspect(
            String bucket,
            String objectKey,
            int prefixLength,
            long maximumBytes) {
        try {
            StatObjectResponse stat = minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
            if (stat.size() <= 0 || stat.size() > maximumBytes) {
                return new StoredObject(
                        stat.size(), stat.contentType(), new byte[0], null, stat.etag());
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteArrayOutputStream prefix = new ByteArrayOutputStream(prefixLength);
            long totalBytes = 0;
            try (GetObjectResponse response = minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .matchETag(stat.etag())
                    .build())) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = response.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                    int prefixRemaining = prefixLength - prefix.size();
                    if (prefixRemaining > 0) {
                        prefix.write(buffer, 0, Math.min(prefixRemaining, read));
                    }
                    totalBytes += read;
                    if (totalBytes > maximumBytes) {
                        return new StoredObject(
                                totalBytes, stat.contentType(), new byte[0], null, stat.etag());
                    }
                }
            }
            if (totalBytes != stat.size()) {
                throw new IllegalStateException("Attachment object length changed during inspection");
            }
            return new StoredObject(
                    stat.size(),
                    stat.contentType(),
                    prefix.toByteArray(),
                    HexFormat.of().formatHex(digest.digest()),
                    stat.etag());
        } catch (ErrorResponseException exception) {
            throw storageException(exception);
        } catch (Exception exception) {
            throw new ChatException(ChatError.ATTACHMENT_STORAGE_UNAVAILABLE, exception);
        }
    }

    @Override
    public InputStream open(String bucket, String objectKey) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (ErrorResponseException exception) {
            throw storageException(exception);
        } catch (Exception exception) {
            throw new ChatException(ChatError.ATTACHMENT_STORAGE_UNAVAILABLE, exception);
        }
    }

    @Override
    public void remove(String bucket, String objectKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (ErrorResponseException exception) {
            String code = exception.errorResponse().code();
            if (!"NoSuchKey".equals(code) && !"NoSuchObject".equals(code)) {
                throw new ChatException(ChatError.ATTACHMENT_STORAGE_UNAVAILABLE, exception);
            }
        } catch (Exception exception) {
            throw new ChatException(ChatError.ATTACHMENT_STORAGE_UNAVAILABLE, exception);
        }
    }

    private String presignedUrl(Method method, String bucket, String objectKey, Duration expiry) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(method)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(Math.toIntExact(expiry.toSeconds()))
                    .build());
        } catch (Exception exception) {
            throw new ChatException(ChatError.ATTACHMENT_STORAGE_UNAVAILABLE, exception);
        }
    }

    private ChatException storageException(ErrorResponseException exception) {
        String code = exception.errorResponse().code();
        if ("NoSuchKey".equals(code) || "NoSuchObject".equals(code)) {
            return new ChatException(ChatError.ATTACHMENT_OBJECT_MISSING, exception);
        }
        if ("PreconditionFailed".equals(code)) {
            return new ChatException(ChatError.ATTACHMENT_OBJECT_MISMATCH, exception);
        }
        return new ChatException(ChatError.ATTACHMENT_STORAGE_UNAVAILABLE, exception);
    }
}
