package com.ecommerce.trade.infrastructure.messaging;

import com.ecommerce.trade.application.model.TradeModels.AfterSaleFulfillmentEventCommand;
import com.ecommerce.trade.application.service.AfterSaleService;
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
@ConditionalOnProperty(
        prefix = "ecommerce.trade.after-sale-fulfillment-consumer", name = "enabled", havingValue = "true")
public class AfterSaleFulfillmentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AfterSaleFulfillmentEventConsumer.class);
    private static final String TAGS = "ReturnShipmentSubmitted||ReturnReceived";
    private static final Set<String> EVENT_TYPES = Set.of("ReturnShipmentSubmitted", "ReturnReceived");

    private final AfterSaleFulfillmentConsumerProperties properties;
    private final AfterSaleService afterSaleService;
    private final ObjectMapper objectMapper;
    private final ClientServiceProvider provider = ClientServiceProvider.loadService();
    private final AtomicLong nextWarningAt = new AtomicLong();
    private volatile SimpleConsumer consumer;

    public AfterSaleFulfillmentEventConsumer(
            AfterSaleFulfillmentConsumerProperties properties,
            AfterSaleService afterSaleService,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.afterSaleService = afterSaleService;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${ecommerce.trade.after-sale-fulfillment-consumer.fixed-delay:1000}")
    public void consume() {
        SimpleConsumer active;
        try {
            active = consumer();
            for (MessageView message : active.receive(properties.batchSize(), properties.invisibleDuration())) {
                try {
                    afterSaleService.applyFulfillmentEvent(parse(message));
                    active.ack(message);
                } catch (Exception exception) {
                    log.warn("After-sale fulfillment event failed and will be retried: messageId={}",
                            message.getMessageId(), exception);
                }
            }
            nextWarningAt.set(0);
        } catch (Exception exception) {
            resetConsumer();
            warnUnavailable(exception);
        }
    }

    private AfterSaleFulfillmentEventCommand parse(MessageView message) throws Exception {
        JsonNode envelope = objectMapper.readTree(readBody(message.getBody()));
        String eventType = requiredText(envelope, "eventType");
        if (!EVENT_TYPES.contains(eventType)) {
            throw new IllegalArgumentException("Unexpected after-sale fulfillment event type");
        }
        JsonNode payload = envelope.path("payload");
        return new AfterSaleFulfillmentEventCommand(
                requiredText(envelope, "eventId"), eventType,
                requiredText(payload, "afterSaleNo"), requiredText(payload, "returnReceiptNo"),
                requiredText(payload, "orderNo"), requiredPositiveLong(payload, "userId"));
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
                                new FilterExpression(TAGS, FilterExpressionType.TAG)))
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
                    log.debug("After-sale fulfillment consumer close failed", ignored);
                }
            }
        }
    }

    private void warnUnavailable(Exception exception) {
        long now = System.currentTimeMillis();
        long due = nextWarningAt.get();
        if (now >= due && nextWarningAt.compareAndSet(due, now + 60_000)) {
            log.warn("After-sale fulfillment consumer unavailable; trade service remains online", exception);
        } else {
            log.debug("After-sale fulfillment consumer reconnect failed", exception);
        }
    }

    @PreDestroy
    void close() {
        resetConsumer();
    }
}
