package com.ecommerce.payment.infrastructure.messaging;

import com.ecommerce.payment.application.port.DomainEventPublisher;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
@ConditionalOnProperty(prefix = "ecommerce.payment.outbox", name = "enabled", havingValue = "true")
public class RocketMqDomainEventPublisher implements DomainEventPublisher {

    private final OutboxProperties properties;
    private final ClientServiceProvider provider = ClientServiceProvider.loadService();
    private volatile Producer producer;

    public RocketMqDomainEventPublisher(OutboxProperties properties) {
        this.properties = properties;
    }

    @Override
    public void publish(String eventId, String eventType, String payload) throws Exception {
        Message message = provider.newMessageBuilder()
                .setTopic(properties.topic())
                .setTag(eventType)
                .setKeys(eventId)
                .setBody(payload.getBytes(StandardCharsets.UTF_8))
                .build();
        producer().send(message);
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
                        .setTopics(properties.topic())
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
