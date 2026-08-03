package com.ecommerce.trade.infrastructure.messaging;

import com.ecommerce.trade.application.port.DomainEventPublisher;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

@Component
@ConditionalOnProperty(prefix = "ecommerce.trade.outbox", name = "enabled", havingValue = "true")
public class RocketMqDomainEventPublisher implements DomainEventPublisher {

    private final OutboxProperties properties;
    private final ClientServiceProvider provider = ClientServiceProvider.loadService();
    private volatile Producer producer;

    public RocketMqDomainEventPublisher(OutboxProperties properties) {
        this.properties = properties;
    }

    @Override
    public void publish(String eventId, String eventType, String payload) throws Exception {
        publishAsync(eventId, eventType, payload).get();
    }

    @Override
    public CompletableFuture<Void> publishAsync(String eventId, String eventType, String payload) {
        return publishAsync(properties.topic(), eventId, eventType, payload);
    }

    @Override
    public CompletableFuture<Void> publishAsync(
            String destinationTopic,
            String eventId,
            String eventType,
            String payload) {
        try {
            Message message = message(destinationTopic, eventId, eventType, payload);
            return producer().sendAsync(message).thenApply(ignored -> null);
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private Message message(String destinationTopic, String eventId, String eventType, String payload) {
        return provider.newMessageBuilder()
                .setTopic(destinationTopic)
                .setTag(eventType)
                .setKeys(eventId)
                .setBody(payload.getBytes(StandardCharsets.UTF_8))
                .build();
    }

    private Producer producer() throws Exception {
        Producer current = producer;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (producer == null) {
                ClientConfiguration configuration = ClientConfiguration.newBuilder()
                        .setEndpoints(properties.endpoints())
                        .enableSsl(false)
                        .build();
                producer = provider.newProducerBuilder()
                        .setClientConfiguration(configuration)
                        .setTopics(properties.topic(), properties.flashSaleTopic())
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
