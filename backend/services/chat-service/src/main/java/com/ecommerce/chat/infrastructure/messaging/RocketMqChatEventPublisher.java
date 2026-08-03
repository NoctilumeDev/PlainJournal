package com.ecommerce.chat.infrastructure.messaging;

import com.ecommerce.chat.application.port.ChatEventPublisher;
import com.ecommerce.chat.infrastructure.realtime.ChatRealtimeProperties;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

@Component
public class RocketMqChatEventPublisher implements ChatEventPublisher {

    private final ChatOutboxProperties outboxProperties;
    private final ChatRealtimeProperties realtimeProperties;
    private final ClientServiceProvider provider = ClientServiceProvider.loadService();
    private volatile Producer producer;

    public RocketMqChatEventPublisher(
            ChatOutboxProperties outboxProperties,
            ChatRealtimeProperties realtimeProperties) {
        this.outboxProperties = outboxProperties;
        this.realtimeProperties = realtimeProperties;
    }

    @Override
    public CompletableFuture<Void> publish(
            String topic,
            String eventId,
            String eventType,
            String payload) {
        try {
            Message message = provider.newMessageBuilder()
                    .setTopic(topic)
                    .setTag(eventType)
                    .setKeys(eventId)
                    .setBody(payload.getBytes(StandardCharsets.UTF_8))
                    .build();
            return producer().sendAsync(message).thenApply(ignored -> null);
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private Producer producer() throws Exception {
        Producer current = producer;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (producer == null) {
                ClientConfiguration configuration = ClientConfiguration.newBuilder()
                        .setEndpoints(outboxProperties.endpoints())
                        .enableSsl(false)
                        .build();
                producer = provider.newProducerBuilder()
                        .setClientConfiguration(configuration)
                        .setTopics(outboxProperties.topic(), realtimeProperties.deliveryTopic())
                        .build();
            }
            return producer;
        }
    }

    @PreDestroy
    void close() throws Exception {
        Producer current = producer;
        if (current != null) {
            current.close();
        }
    }
}
