package com.ecommerce.trade.infrastructure.messaging;

import com.ecommerce.trade.application.model.TradeModels.PaymentSucceededCommand;
import com.ecommerce.trade.application.service.TradeOrderService;
import com.ecommerce.platform.common.observability.ConsumerFailureRetryHandler;
import com.ecommerce.platform.common.observability.MessagingTracing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import io.micrometer.tracing.Span;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(prefix = "ecommerce.trade.payment-consumer", name = "enabled", havingValue = "true")
public class PaymentSucceededConsumer implements ConsumerFailureRetryHandler {

    private static final Logger log = LoggerFactory.getLogger(PaymentSucceededConsumer.class);

    private final PaymentEventConsumerProperties properties;
    private final TradeOrderService orderService;
    private final ObjectMapper objectMapper;
    private final ConsumerFailureRecorder failureRecorder;
    private final MessagingTracing messagingTracing;
    private final ProcessTerminationFaultInjector faultInjector;
    private final Counter acknowledgements;
    private final Counter redeliveryAcknowledgements;
    private final ClientServiceProvider provider = ClientServiceProvider.loadService();
    private final AtomicLong nextWarningAt = new AtomicLong();
    private volatile SimpleConsumer consumer;

    public PaymentSucceededConsumer(
            PaymentEventConsumerProperties properties,
            TradeOrderService orderService,
            ObjectMapper objectMapper,
            ConsumerFailureRecorder failureRecorder,
            MessagingTracing messagingTracing,
            ProcessTerminationFaultInjector faultInjector,
            MeterRegistry meterRegistry) {
        this.properties = properties;
        this.orderService = orderService;
        this.objectMapper = objectMapper;
        this.failureRecorder = failureRecorder;
        this.messagingTracing = messagingTracing;
        this.faultInjector = faultInjector;
        this.acknowledgements = Counter.builder("ecommerce.messaging.consumer.acknowledgements")
                .description("RocketMQ messages acknowledged after successful business processing")
                .tag("service", "trade-service")
                .tag("consumer_group", properties.consumerGroup())
                .tag("event_type", "PaymentSucceeded")
                .register(meterRegistry);
        this.redeliveryAcknowledgements = Counter.builder(
                        "ecommerce.messaging.consumer.redelivery.acknowledgements")
                .description("Redelivered RocketMQ messages acknowledged after idempotent processing")
                .tag("service", "trade-service")
                .tag("consumer_group", properties.consumerGroup())
                .tag("event_type", "PaymentSucceeded")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${ecommerce.trade.payment-consumer.fixed-delay:1000}")
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
        PaymentSucceededCommand command;
        try {
            command = parse(message);
        } catch (Exception exception) {
            failureRecorder.recordTerminal(message, properties.consumerGroup(), exception);
            active.ack(message);
            log.error("PaymentSucceeded payload is not retryable and requires attention: messageId={}",
                    message.getMessageId(), exception);
            return;
        }
        try {
            messagingTracing.inSpan(
                    "rocketmq consume PaymentSucceeded",
                    Span.Kind.CONSUMER,
                    message.getProperties(),
                    Map.of(
                            "messaging.system", "rocketmq",
                            "messaging.destination.name", properties.topic(),
                            "messaging.operation", "process",
                            "messaging.message.id", message.getMessageId().toString(),
                            "messaging.event.type", "PaymentSucceeded"),
                    () -> {
                        orderService.applyPaymentSucceeded(command);
                        faultInjector.terminateIfArmed(
                                ProcessTerminationPoint.CONSUMER_AFTER_COMMIT, command.eventId());
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
                log.error("PaymentSucceeded processing exhausted retries and requires attention: messageId={}",
                        message.getMessageId(), exception);
            } else {
                log.warn("PaymentSucceeded processing failed; durable MySQL retry now owns "
                                + "recovery: messageId={}",
                        message.getMessageId(), exception);
            }
        }
    }

    private PaymentSucceededCommand parse(MessageView message) throws Exception {
        return parseEnvelope(objectMapper.readTree(readBody(message.getBody())));
    }

    @Override
    public String consumerGroup() {
        return properties.consumerGroup();
    }

    @Override
    public void retry(String rawPayload) throws Exception {
        orderService.applyPaymentSucceeded(
                parseEnvelope(objectMapper.readTree(rawPayload)));
    }

    private PaymentSucceededCommand parseEnvelope(JsonNode envelope) {
        if (envelope.path("payloadVersion").asInt(-1) != 1) {
            throw new IllegalArgumentException("Unsupported payment event payload version");
        }
        if (!"PaymentSucceeded".equals(envelope.path("eventType").asText())) {
            throw new IllegalArgumentException("Unexpected payment event type");
        }
        JsonNode payload = envelope.path("payload");
        return new PaymentSucceededCommand(
                requiredText(envelope, "eventId"),
                requiredText(payload, "paymentNo"),
                requiredText(payload, "orderNo"),
                requiredPositiveLong(payload, "userId"),
                requiredText(payload, "reservationNo"),
                new BigDecimal(requiredText(payload, "amount")));
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
                        .setEndpoints(properties.endpoints())
                        .enableSsl(false)
                        .build();
                consumer = provider.newSimpleConsumerBuilder()
                        .setClientConfiguration(configuration)
                        .setConsumerGroup(properties.consumerGroup())
                        .setAwaitDuration(properties.awaitDuration())
                        .setSubscriptionExpressions(Map.of(
                                properties.topic(),
                                new FilterExpression("PaymentSucceeded", FilterExpressionType.TAG)))
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
                    log.debug("Payment consumer close failed", ignored);
                }
            }
        }
    }

    private void warnUnavailable(Exception exception) {
        long now = System.currentTimeMillis();
        long due = nextWarningAt.get();
        if (now >= due && nextWarningAt.compareAndSet(due, now + 60_000)) {
            log.warn("Payment event consumer is unavailable; trade service remains online", exception);
        } else {
            log.debug("Payment event consumer reconnect attempt failed", exception);
        }
    }

    @PreDestroy
    void close() {
        resetConsumer();
    }
}
