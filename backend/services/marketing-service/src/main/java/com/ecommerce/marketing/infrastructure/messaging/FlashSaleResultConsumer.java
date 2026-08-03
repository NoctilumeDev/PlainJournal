package com.ecommerce.marketing.infrastructure.messaging;

import com.ecommerce.marketing.application.model.FlashSaleModels.FlashSaleOrderResultCommand;
import com.ecommerce.marketing.application.service.FlashSaleResultHandler;
import com.ecommerce.platform.common.observability.ConsumerFailureRetryHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(
        prefix = "ecommerce.marketing.flash-sale-result-consumer",
        name = "enabled",
        havingValue = "true")
public class FlashSaleResultConsumer implements ConsumerFailureRetryHandler {

    private static final Logger log = LoggerFactory.getLogger(FlashSaleResultConsumer.class);
    private static final Set<String> EVENT_TYPES =
            Set.of("FlashSaleOrderSucceeded", "FlashSaleOrderFailed");

    private final FlashSaleResultConsumerProperties properties;
    private final FlashSaleResultHandler handler;
    private final ObjectMapper objectMapper;
    private final ConsumerFailureRecorder failureRecorder;
    private final ClientServiceProvider provider = ClientServiceProvider.loadService();
    private final AtomicLong nextWarningAt = new AtomicLong();
    private volatile SimpleConsumer consumer;

    public FlashSaleResultConsumer(
            FlashSaleResultConsumerProperties properties,
            FlashSaleResultHandler handler,
            ObjectMapper objectMapper,
            ConsumerFailureRecorder failureRecorder) {
        this.properties = properties;
        this.handler = handler;
        this.objectMapper = objectMapper;
        this.failureRecorder = failureRecorder;
    }

    @Scheduled(fixedDelayString = "${ecommerce.marketing.flash-sale-result-consumer.fixed-delay:500}")
    public void consume() {
        SimpleConsumer active;
        try {
            active = consumer();
            for (MessageView message : active.receive(
                    properties.batchSize(), properties.invisibleDuration())) {
                FlashSaleOrderResultCommand command;
                try {
                    command = parse(message);
                } catch (Exception exception) {
                    failureRecorder.recordTerminal(message, properties.consumerGroup(), exception);
                    active.ack(message);
                    log.error("Flash-sale result payload requires attention: messageId={}",
                            message.getMessageId(), exception);
                    continue;
                }
                try {
                    handler.handle(command);
                    failureRecorder.markRecovered(message, properties.consumerGroup());
                    active.ack(message);
                } catch (Exception exception) {
                    boolean terminal = failureRecorder.record(
                            message, properties.consumerGroup(), exception);
                    active.ack(message);
                    if (terminal) {
                        log.error("Flash-sale result processing exhausted retries: messageId={}",
                                message.getMessageId(), exception);
                    } else {
                        log.warn("Flash-sale result processing failed; durable MySQL retry now owns "
                                        + "recovery: messageId={}",
                                message.getMessageId(), exception);
                    }
                }
            }
            nextWarningAt.set(0);
        } catch (Exception exception) {
            resetConsumer();
            warnUnavailable(exception);
        }
    }

    private FlashSaleOrderResultCommand parse(MessageView message) throws Exception {
        return parseEnvelope(objectMapper.readTree(readBody(message.getBody())));
    }

    @Override
    public String consumerGroup() {
        return properties.consumerGroup();
    }

    @Override
    public void retry(String rawPayload) throws Exception {
        handler.handle(parseEnvelope(objectMapper.readTree(rawPayload)));
    }

    private FlashSaleOrderResultCommand parseEnvelope(JsonNode envelope) {
        String eventType = requiredText(envelope, "eventType");
        if (!EVENT_TYPES.contains(eventType)) {
            throw new IllegalArgumentException("Unexpected flash-sale result event type");
        }
        JsonNode version = envelope.get("payloadVersion");
        if (version == null || !version.canConvertToInt() || version.intValue() != 1) {
            throw new IllegalArgumentException("Unsupported flash-sale result payload version");
        }
        JsonNode payload = envelope.path("payload");
        return new FlashSaleOrderResultCommand(
                requiredText(envelope, "eventId"),
                eventType,
                requiredText(payload, "requestToken"),
                requiredText(payload, "activityNo"),
                requiredLong(payload, "userId"),
                optionalText(payload, "orderNo"),
                optionalText(payload, "failureCode"),
                Instant.parse(requiredText(payload, "completedAt")));
    }

    private String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null) {
            throw new IllegalArgumentException("Missing event field: " + field);
        }
        return value;
    }

    private String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    private Long requiredLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToLong() || value.longValue() <= 0) {
            throw new IllegalArgumentException("Missing event field: " + field);
        }
        return value.longValue();
    }

    private byte[] readBody(ByteBuffer source) {
        ByteBuffer copy = source.asReadOnlyBuffer();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return bytes;
    }

    private SimpleConsumer consumer() throws Exception {
        SimpleConsumer current = consumer;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (consumer == null) {
                ClientConfiguration configuration = ClientConfiguration.newBuilder()
                        .setEndpoints(properties.endpoints())
                        .enableSsl(false)
                        .build();
                consumer = provider.newSimpleConsumerBuilder()
                        .setClientConfiguration(configuration)
                        .setConsumerGroup(properties.consumerGroup())
                        .setAwaitDuration(properties.awaitDuration())
                        .setSubscriptionExpressions(Map.of(
                                properties.topic(),
                                new FilterExpression(
                                        "FlashSaleOrderSucceeded||FlashSaleOrderFailed",
                                        FilterExpressionType.TAG)))
                        .build();
            }
            return consumer;
        }
    }

    private void resetConsumer() {
        synchronized (this) {
            SimpleConsumer current = consumer;
            consumer = null;
            if (current != null) {
                try {
                    current.close();
                } catch (Exception ignored) {
                    log.debug("Flash-sale result consumer close failed", ignored);
                }
            }
        }
    }

    private void warnUnavailable(Exception exception) {
        long now = System.currentTimeMillis();
        long due = nextWarningAt.get();
        if (now >= due && nextWarningAt.compareAndSet(due, now + 60_000)) {
            log.warn("Flash-sale result consumer is unavailable; marketing service remains online", exception);
        } else {
            log.debug("Flash-sale result consumer reconnect attempt failed", exception);
        }
    }

    @PreDestroy
    void close() {
        resetConsumer();
    }
}
