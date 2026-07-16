package com.ecommerce.catalog.application.port;

import java.time.Duration;

public interface ObjectStorage {

    String createUploadUrl(String bucket, String objectKey, Duration expiry);

    String createDownloadUrl(String bucket, String objectKey, Duration expiry);

    StoredObject stat(String bucket, String objectKey);

    record StoredObject(long sizeBytes, String contentType) {
    }
}
