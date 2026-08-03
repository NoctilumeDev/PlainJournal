package com.ecommerce.trade.infrastructure.messaging;

import com.ecommerce.platform.common.observability.MessagingTracing;
import com.ecommerce.trade.application.model.TradeModels.FlashSaleAdmissionAcceptedCommand;
import com.ecommerce.trade.application.service.FlashSaleOrderService;
import com.ecommerce.platform.common.observability.ConsumerFailureRetryHandler;
import com.ecommerce.trade.infrastructure.config.TradeSchedulingConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
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
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(
        prefix = "ecommerce.trade.flash-sale-consumer",
        name = "enabled",
        havingValue = "true")
public class FlashSaleAdmissionConsumer implements ConsumerFailureRetryHandler {

    private static final Logger log = LoggerFactory.getLogger(FlashSaleAdmissionConsumer.class);

    private final FlashSaleConsumerProperties properties;
    private final FlashSaleOrderService orderService;
    private final ObjectMapper objectMapper;
    private final ConsumerFailureRecorder failureRecorder;
    private final MessagingTracing messagingTracing;
    private final Counter acknowledgements;
    private final Counter redeliveryAcknowledgements;
    private final ClientServiceProvider provider = ClientServiceProvider.loadService();
    private final AtomicLong nextWarningAt = new AtomicLong();
    private volatile SimpleConsumer consumer;

    public FlashSaleAdmissionConsumer(
            FlashSaleConsumerProperties properties,
            FlashSaleOrderService orderService,
            ObjectMapper objectMapper,
            ConsumerFailureRecorder failureRecorder,
            MessagingTracing messagingTracing,
            MeterRegistry meterRegistry) {
        this.properties = properties;
        this.orderService = orderService;
        this.objectMapper = objectMapper;
        this.failureRecorder = failureRecorder;
        this.messagingTracing = messagingTracing;
        this.acknowledgements = Counter.builder("ecommerce.messaging.consumer.acknowledgements")
                .description("RocketMQ messages acknowledged after successful business processing")
                .tag("service", "trade-service")
                .tag("consumer_group", properties.consumerGroup())
                .tag("event_type", "FlashSaleAdmissionAccepted")
                .register(meterRegistry);
        this.redeliveryAcknowledgements = Counter.builder(
                        "ecommerce.messaging.consumer.redelivery.acknowledgements")
                .description("Redelivered RocketMQ messages acknowledged after idempotent processing")
                .tag("service", "trade-service")
                .tag("consumer_group", properties.consumerGroup())
                .tag("event_type", "FlashSaleAdmissionAccepted")
                .register(meterRegistry);
    }

    @Scheduled(
            fixedDelayString = "${ecommerce.trade.flash-sale-consumer.fixed-delay:200}",
            scheduler = TradeSchedulingConfig.FLASH_SALE_SCHEDULER)
    public void consume() {
        SimpleConsumer active;
        try {
            active = consumer();
            for (MessageView message : active.receive(
                    properties.batchSize(), properties.invisibleDuration())) {
                processMessage(message, active);
            }
            nextWarningAt.set(0);
        } catch (Exception exception) {
            resetConsumer();
            warnUnavailable(exception);
        }
    }

    void processMessage(MessageView message, SimpleConsumer active) throws Exception {
        FlashSaleAdmissionAcceptedCommand command;
        try {
            command = parse(message);
        } catch (Exception exception) {
            failureRecorder.recordTerminal(message, properties.consumerGroup(), exception);
            active.ack(message);
            log.error("FlashSaleAdmissionAccepted payload requires attention: messageId={}",
                    message.getMessageId(), exception);
            return;
        }
        try {
            messagingTracing.inSpan(
                    "rocketmq consume FlashSaleAdmissionAccepted",
                    Span.Kind.CONSUMER,
                    message.getProperties(),
                    Map.of(
                            "messaging.system", "rocketmq",
                            "messaging.destination.name", properties.topic(),
                            "messaging.operation", "process",
                            "messaging.message.id", message.getMessageId().toString(),
                            "messaging.event.type", "FlashSaleAdmissionAccepted"),
                    () -> {
                        orderService.handle(command);
                        failureRecorder.markRecovered(message, properties.consumerGroup());
                        active.ack(message);
                        acknowledgements.increment();
                        if (message.getDeliveryAttempt() > 1) {
                            redeliveryAcknowledgements.increment();
                        }
                    });
        } catch (Exception exception) {
            boolean terminal = failureRecorder.record(message, properties.consumerGroup(), exception);
            active.ack(message);
            if (terminal) {
                log.error("Flash-sale admission processing exhausted retries: messageId={}",
                        message.getMessageId(), exception);
            } else {
                log.warn("Flash-sale admission processing failed; durable MySQL retry now owns "
                                + "recovery: messageId={}",
                        message.getMessageId(), exception);
            }
        }
    }

    private FlashSaleAdmissionAcceptedCommand parse(MessageView message) throws Exception {
        return parseEnvelope(objectMapper.readTree(readBody(message.getBody())));
    }

    @Override
    public String consumerGroup() {
        return properties.consumerGroup();
    }

    @Override
    public void retry(String rawPayload) throws Exception {
        orderService.handle(parseEnvelope(objectMapper.readTree(rawPayload)));
    }

    private FlashSaleAdmissionAcceptedCommand parseEnvelope(JsonNode envelope) {
        if (!"FlashSaleAdmissionAccepted".equals(envelope.path("eventType").asText())) {
            throw new IllegalArgumentException("Unexpected flash-sale event type");
        }
        JsonNode version = envelope.get("payloadVersion");
        if (version == null || !version.canConvertToInt() || version.intValue() != 1) {
            throw new IllegalArgumentException("Unsupported flash-sale admission payload version");
        }
        JsonNode payload = envelope.path("payload");
        return new FlashSaleAdmissionAcceptedCommand(
                requiredText(envelope, "eventId"),
                requiredText(payload, "requestToken"),
                requiredText(payload, "activityNo"),
                requiredLong(payload, "userId"),
                requiredLong(payload, "addressId"),
                requiredLong(payload, "productId"),
                requiredLong(payload, "skuId"),
                new BigDecimal(requiredText(payload, "salePrice")),
                Instant.parse(requiredText(payload, "acceptedAt")),
                Instant.parse(requiredText(payload, "activityEndsAt")));
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Missing event field: " + field);
        }
        return value.asText();
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
                                        "FlashSaleAdmissionAccepted",
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
                    log.debug("Flash-sale consumer close failed", ignored);
                }
            }
        }
    }

    private void warnUnavailable(Exception exception) {
        long now = System.currentTimeMillis();
        long due = nextWarningAt.get();
        if (now >= due && nextWarningAt.compareAndSet(due, now + 60_000)) {
            log.warn("Flash-sale event consumer is unavailable; trade service remains online", exception);
        } else {
            log.debug("Flash-sale event consumer reconnect attempt failed", exception);
        }
    }

    @PreDestroy
    void close() {
        resetConsumer();
    }
}
