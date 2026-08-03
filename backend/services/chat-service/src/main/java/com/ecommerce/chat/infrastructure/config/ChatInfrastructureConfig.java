package com.ecommerce.chat.infrastructure.config;

import com.ecommerce.chat.infrastructure.messaging.ChatOutboxProperties;
import com.ecommerce.chat.infrastructure.messaging.ChatConsumerFailureRetryProperties;
import com.ecommerce.chat.infrastructure.persistence.mapper.ConsumerFailureMapper;
import com.ecommerce.chat.infrastructure.realtime.ChatRealtimeProperties;
import com.ecommerce.chat.infrastructure.realtime.ChatWebSocketTicketProperties;
import com.ecommerce.chat.infrastructure.storage.ChatAttachmentStorageProperties;
import com.ecommerce.chat.infrastructure.storage.ChatAttachmentScanProperties;
import com.ecommerce.platform.common.observability.ConsumerFailureObservability;
import io.minio.MinioClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.security.SecureRandom;
import java.time.Clock;

@Configuration
@EnableConfigurationProperties({
        ChatConsumerFailureRetryProperties.class,
        ChatOutboxProperties.class,
        ChatRealtimeProperties.class,
        ChatWebSocketTicketProperties.class,
        ChatAttachmentScanProperties.class,
        ChatAttachmentStorageProperties.class
})
public class ChatInfrastructureConfig {

    @Bean
    public ConsumerFailureObservability chatConsumerFailureObservability(
            MeterRegistry meterRegistry,
            ConsumerFailureMapper mapper,
            Clock chatClock) {
        return new ConsumerFailureObservability(
                meterRegistry,
                "chat-service",
                mapper,
                chatClock);
    }

    @Bean
    public MinioClient chatMinioClient(ChatAttachmentStorageProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.endpoint())
                .credentials(properties.username(), properties.password())
                .build();
    }

    @Bean
    public SecureRandom chatWebSocketTicketSecureRandom() {
        return new SecureRandom();
    }
}
