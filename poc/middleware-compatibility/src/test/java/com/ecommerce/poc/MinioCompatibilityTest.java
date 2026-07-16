package com.ecommerce.poc;

import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.Http.Method;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MinioCompatibilityTest extends BaseCompatibilityTest {

    @Value("${poc.minio.endpoint}")
    private String endpoint;

    @Value("${poc.minio.username}")
    private String username;

    @Value("${poc.minio.password}")
    private String password;

    @Value("${poc.minio.bucket}")
    private String bucket;

    @Test
    void uploadsReadsAndSignsAPrivateObject() throws Exception {
        MinioClient client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(username, password)
                .build();
        byte[] content = "middleware-compatible".getBytes(StandardCharsets.UTF_8);
        String objectName = "poc/" + UUID.randomUUID() + ".txt";

        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .contentType("text/plain")
                    .stream(new ByteArrayInputStream(content), (long) content.length, -1L)
                    .build());

            try (GetObjectResponse response = client.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .build())) {
                assertThat(response.readAllBytes()).isEqualTo(content);
            }

            String signedUrl = client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectName)
                    .expiry(60)
                    .build());
            assertThat(signedUrl).contains(objectName).contains("X-Amz-Signature");
        }
        finally {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .build());
        }
    }
}
