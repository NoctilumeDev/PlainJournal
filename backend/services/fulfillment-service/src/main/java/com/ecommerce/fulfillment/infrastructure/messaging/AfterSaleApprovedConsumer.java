package com.ecommerce.fulfillment.infrastructure.messaging;

import com.ecommerce.fulfillment.application.model.FulfillmentModels.AfterSaleApprovedCommand;
import com.ecommerce.fulfillment.application.model.FulfillmentModels.AfterSaleApprovedItem;
import com.ecommerce.fulfillment.application.service.ReturnReceiptService;
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

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(prefix = "ecommerce.fulfillment.after-sale-consumer", name = "enabled", havingValue = "true")
public class AfterSaleApprovedConsumer implements ConsumerFailureRetryHandler {

    private static final Logger log = LoggerFactory.getLogger(AfterSaleApprovedConsumer.class);

    private final AfterSaleEventConsumerProperties properties;
    private final ReturnReceiptService returnReceiptService;
    private final ObjectMapper objectMapper;
    private final ConsumerFailureRecorder failureRecorder;
    private final ClientServiceProvider provider = ClientServiceProvider.loadService();
    private final AtomicLong nextWarningAt = new AtomicLong();
    private volatile SimpleConsumer consumer;

    public AfterSaleApprovedConsumer(
            AfterSaleEventConsumerProperties properties,
            ReturnReceiptService returnReceiptService,
            ObjectMapper objectMapper,
            ConsumerFailureRecorder failureRecorder) {
        this.properties = properties;
        this.returnReceiptService = returnReceiptService;
        this.objectMapper = objectMapper;
        this.failureRecorder = failureRecorder;
    }

    @Scheduled(fixedDelayString = "${ecommerce.fulfillment.after-sale-consumer.fixed-delay:1000}")
    public void consume() {
        SimpleConsumer active;
        try {
            active = consumer();
            for (MessageView message : active.receive(properties.batchSize(), properties.invisibleDuration())) {
                AfterSaleApprovedCommand command;
                try {
                    command = parse(message);
                } catch (Exception exception) {
                    failureRecorder.recordTerminal(message, properties.consumerGroup(), exception);
                    active.ack(message);
                    log.error("AfterSaleApproved payload requires attention: messageId={}",
                            message.getMessageId(), exception);
                    continue;
                }
                try {
                    returnReceiptService.createFromAfterSaleApproved(command);
                    failureRecorder.markRecovered(message, properties.consumerGroup());
                    active.ack(message);
                } catch (Exception exception) {
                    boolean terminal = failureRecorder.record(
                            message, properties.consumerGroup(), exception);
                    active.ack(message);
                    if (terminal) {
                        log.error("AfterSaleApproved moved to the local compensation queue: messageId={}",
                                message.getMessageId(), exception);
                    } else {
                        log.warn("AfterSaleApproved processing failed; durable MySQL retry now owns "
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

    private AfterSaleApprovedCommand parse(MessageView message) throws Exception {
        return parseEnvelope(objectMapper.readTree(readBody(message.getBody())));
    }

    @Override
    public String consumerGroup() {
        return properties.consumerGroup();
    }

    @Override
    public void retry(String rawPayload) throws Exception {
        returnReceiptService.createFromAfterSaleApproved(
                parseEnvelope(objectMapper.readTree(rawPayload)));
    }

    private AfterSaleApprovedCommand parseEnvelope(JsonNode envelope) {
        if (envelope.path("payloadVersion").asInt(-1) != 1) {
            throw new IllegalArgumentException("Unsupported AfterSaleApproved payload version");
        }
        if (!"AfterSaleApproved".equals(envelope.path("eventType").asText())) {
            throw new IllegalArgumentException("Unexpected after-sale event type");
        }
        JsonNode payload = envelope.path("payload");
        JsonNode itemNodes = payload.path("items");
        if (!itemNodes.isArray() || itemNodes.isEmpty()) {
            throw new IllegalArgumentException("Missing after-sale items");
        }
        List<AfterSaleApprovedItem> items = new ArrayList<>();
        for (JsonNode item : itemNodes) {
            items.add(new AfterSaleApprovedItem(
                    requiredPositiveInt(item, "lineNo"),
                    requiredPositiveLong(item, "skuId"),
                    requiredPositiveLong(item, "quantity"),
                    requiredAmount(item, "refundableAmount")));
        }
        return new AfterSaleApprovedCommand(
                requiredText(envelope, "eventId"),
                requiredText(payload, "afterSaleNo"),
                requiredText(payload, "orderNo"),
                requiredPositiveLong(payload, "userId"),
                requiredPositiveLong(payload, "warehouseId"),
                requiredText(payload, "reservationNo"),
                requiredAmount(payload, "refundAmount"),
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

    private BigDecimal requiredAmount(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            throw new IllegalArgumentException("Missing or invalid event field: " + field);
        }
        BigDecimal amount = value.decimalValue();
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("Invalid event field: " + field);
        }
        return amount;
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
                        .setSubscriptionExpressions(Map.of(properties.topic(),
                                new FilterExpression("AfterSaleApproved", FilterExpressionType.TAG)))
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
                    log.debug("After-sale consumer close failed", ignored);
                }
            }
        }
    }

    private void warnUnavailable(Exception exception) {
        long now = System.currentTimeMillis();
        long due = nextWarningAt.get();
        if (now >= due && nextWarningAt.compareAndSet(due, now + 60_000)) {
            log.warn("After-sale event consumer is unavailable; fulfillment service remains online", exception);
        } else {
            log.debug("After-sale event consumer reconnect attempt failed", exception);
        }
    }

    @PreDestroy
    void close() {
        resetConsumer();
    }
}
