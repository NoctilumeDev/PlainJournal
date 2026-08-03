package com.ecommerce.trade.infrastructure.messaging;

import com.ecommerce.trade.application.model.TradeModels.FulfillmentEventCommand;
import com.ecommerce.trade.application.service.TradeOrderService;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(prefix = "ecommerce.trade.fulfillment-consumer", name = "enabled", havingValue = "true")
public class FulfillmentEventConsumer implements ConsumerFailureRetryHandler {

    private static final Logger log = LoggerFactory.getLogger(FulfillmentEventConsumer.class);
    private static final String TAG_EXPRESSION = "FulfillmentCreated||ShipmentDispatched||ShipmentSigned";
    private static final Set<String> EVENT_TYPES = Set.of(
            "FulfillmentCreated", "ShipmentDispatched", "ShipmentSigned");

    private final FulfillmentEventConsumerProperties properties;
    private final TradeOrderService orderService;
    private final ObjectMapper objectMapper;
    private final ConsumerFailureRecorder failureRecorder;
    private final ClientServiceProvider provider = ClientServiceProvider.loadService();
    private final AtomicLong nextWarningAt = new AtomicLong();
    private volatile SimpleConsumer consumer;

    public FulfillmentEventConsumer(
            FulfillmentEventConsumerProperties properties,
            TradeOrderService orderService,
            ObjectMapper objectMapper,
            ConsumerFailureRecorder failureRecorder) {
        this.properties = properties;
        this.orderService = orderService;
        this.objectMapper = objectMapper;
        this.failureRecorder = failureRecorder;
    }

    @Scheduled(fixedDelayString = "${ecommerce.trade.fulfillment-consumer.fixed-delay:1000}")
    public void consume() {
        SimpleConsumer active;
        try {
            active = consumer();
            for (MessageView message : active.receive(properties.batchSize(), properties.invisibleDuration())) {
                processMessage(message, active);
            }
            nextWarningAt.set(0);
        } catch (Exception exception) {
            resetConsumer();
            warnUnavailable(exception);
        }
    }

    void processMessage(MessageView message, SimpleConsumer active) throws Exception {
        FulfillmentEventCommand command;
        try {
            command = parse(message);
        } catch (Exception exception) {
            failureRecorder.recordTerminal(message, properties.consumerGroup(), exception);
            active.ack(message);
            log.error("Fulfillment event payload is not retryable and requires attention: messageId={}",
                    message.getMessageId(), exception);
            return;
        }
        try {
            orderService.applyFulfillmentEvent(command);
            failureRecorder.markRecovered(message, properties.consumerGroup());
            active.ack(message);
        } catch (Exception exception) {
            boolean terminal = failureRecorder.record(message, properties.consumerGroup(), exception);
            active.ack(message);
            if (terminal) {
                log.error("Fulfillment event processing exhausted retries and requires attention: messageId={}",
                        message.getMessageId(), exception);
            } else {
                log.warn("Fulfillment event processing failed; durable MySQL retry now owns "
                                + "recovery: messageId={}",
                        message.getMessageId(), exception);
            }
        }
    }

    private FulfillmentEventCommand parse(MessageView message) throws Exception {
        return parseEnvelope(objectMapper.readTree(readBody(message.getBody())));
    }

    @Override
    public String consumerGroup() {
        return properties.consumerGroup();
    }

    @Override
    public void retry(String rawPayload) throws Exception {
        orderService.applyFulfillmentEvent(
                parseEnvelope(objectMapper.readTree(rawPayload)));
    }

    private FulfillmentEventCommand parseEnvelope(JsonNode envelope) {
        if (envelope.path("payloadVersion").asInt(-1) != 1) {
            throw new IllegalArgumentException("Unsupported fulfillment event payload version");
        }
        String eventType = requiredText(envelope, "eventType");
        if (!EVENT_TYPES.contains(eventType)) {
            throw new IllegalArgumentException("Unexpected fulfillment event type");
        }
        JsonNode payload = envelope.path("payload");
        return new FulfillmentEventCommand(
                requiredText(envelope, "eventId"),
                eventType,
                requiredText(payload, "fulfillmentNo"),
                requiredText(payload, "orderNo"),
                requiredPositiveLong(payload, "userId"));
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Missing event field: " + field);
        }
        return value.asText();
    }

    private Long requiredPositiveLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToLong() || value.longValue() <= 0) {
            throw new IllegalArgumentException("Missing or invalid event field: " + field);
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
                        .setEndpoints(properties.endpoints()).enableSsl(false).build();
                consumer = provider.newSimpleConsumerBuilder()
                        .setClientConfiguration(configuration)
                        .setConsumerGroup(properties.consumerGroup())
                        .setAwaitDuration(properties.awaitDuration())
                        .setSubscriptionExpressions(Map.of(properties.topic(),
                                new FilterExpression(TAG_EXPRESSION, FilterExpressionType.TAG)))
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
                    log.debug("Fulfillment consumer close failed", ignored);
                }
            }
        }
    }

    private void warnUnavailable(Exception exception) {
        long now = System.currentTimeMillis();
        long due = nextWarningAt.get();
        if (now >= due && nextWarningAt.compareAndSet(due, now + 60_000)) {
            log.warn("Fulfillment event consumer is unavailable; trade service remains online", exception);
        } else {
            log.debug("Fulfillment event consumer reconnect attempt failed", exception);
        }
    }

    @PreDestroy
    void close() {
        resetConsumer();
    }
}
