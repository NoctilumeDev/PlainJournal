package com.ecommerce.catalog.infrastructure.storage;

import com.ecommerce.catalog.application.exception.CatalogError;
import com.ecommerce.catalog.application.exception.CatalogException;
import com.ecommerce.catalog.application.port.ObjectStorage;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.Http.Method;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class MinioObjectStorage implements ObjectStorage {

    private final MinioClient minioClient;

    public MinioObjectStorage(MinioClient minioClient) {
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
    public StoredObject stat(String bucket, String objectKey) {
        try {
            StatObjectResponse response = minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
            return new StoredObject(response.size(), response.contentType());
        } catch (Exception exception) {
            throw new CatalogException(CatalogError.MEDIA_STORAGE_UNAVAILABLE, exception);
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
            throw new CatalogException(CatalogError.MEDIA_STORAGE_UNAVAILABLE, exception);
        }
    }
}
