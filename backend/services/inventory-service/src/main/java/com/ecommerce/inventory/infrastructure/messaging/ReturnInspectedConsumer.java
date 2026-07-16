package com.ecommerce.inventory.infrastructure.messaging;

import com.ecommerce.inventory.application.model.InventoryModels.ReturnInspectedCommand;
import com.ecommerce.inventory.application.model.InventoryModels.ReturnInspectedItem;
import com.ecommerce.inventory.application.service.ReturnStockService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(prefix = "ecommerce.inventory.return-consumer", name = "enabled", havingValue = "true")
public class ReturnInspectedConsumer {

    private static final Logger log = LoggerFactory.getLogger(ReturnInspectedConsumer.class);

    private final ReturnEventConsumerProperties properties;
    private final ReturnStockService returnStockService;
    private final ObjectMapper objectMapper;
    private final ClientServiceProvider provider = ClientServiceProvider.loadService();
    private final AtomicLong nextWarningAt = new AtomicLong();
    private volatile SimpleConsumer consumer;

    public ReturnInspectedConsumer(
            ReturnEventConsumerProperties properties,
            ReturnStockService returnStockService,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.returnStockService = returnStockService;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${ecommerce.inventory.return-consumer.fixed-delay:1000}")
    public void consume() {
        SimpleConsumer active;
        try {
            active = consumer();
            for (MessageView message : active.receive(properties.batchSize(), properties.invisibleDuration())) {
                try {
                    returnStockService.stock(parse(message));
                    active.ack(message);
                } catch (Exception exception) {
                    log.warn("ReturnInspected processing failed and will be retried: messageId={}",
                            message.getMessageId(), exception);
                }
            }
            nextWarningAt.set(0);
        } catch (Exception exception) {
            resetConsumer();
            warnUnavailable(exception);
        }
    }

    private ReturnInspectedCommand parse(MessageView message) throws Exception {
        JsonNode envelope = objectMapper.readTree(readBody(message.getBody()));
        if (!"ReturnInspected".equals(envelope.path("eventType").asText())) {
            throw new IllegalArgumentException("Unexpected return event type");
        }
        JsonNode payload = envelope.path("payload");
        JsonNode itemNodes = payload.path("items");
        if (!itemNodes.isArray() || itemNodes.isEmpty()) {
            throw new IllegalArgumentException("Missing return items");
        }
        List<ReturnInspectedItem> items = new ArrayList<>();
        for (JsonNode item : itemNodes) {
            items.add(new ReturnInspectedItem(
                    requiredPositiveInt(item, "lineNo"),
                    requiredPositiveLong(item, "skuId"),
                    requiredPositiveLong(item, "quantity")));
        }
        return new ReturnInspectedCommand(
                requiredText(envelope, "eventId"),
                requiredText(payload, "returnReceiptNo"),
                requiredText(payload, "afterSaleNo"),
                requiredText(payload, "orderNo"),
                requiredPositiveLong(payload, "userId"),
                requiredPositiveLong(payload, "warehouseId"),
                requiredText(payload, "reservationNo"),
                items);
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

    private int requiredPositiveInt(JsonNode node, String field) {
        long value = requiredPositiveLong(node, field);
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid event field: " + field);
        }
        return (int) value;
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
                                new FilterExpression("ReturnInspected", FilterExpressionType.TAG)))
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
                    log.debug("Return consumer close failed", ignored);
                }
            }
        }
    }

    private void warnUnavailable(Exception exception) {
        long now = System.currentTimeMillis();
        long due = nextWarningAt.get();
        if (now >= due && nextWarningAt.compareAndSet(due, now + 60_000)) {
            log.warn("Return event consumer is unavailable; inventory service remains online", exception);
        } else {
            log.debug("Return event consumer reconnect attempt failed", exception);
        }
    }

    @PreDestroy
    void close() {
        resetConsumer();
    }
}
