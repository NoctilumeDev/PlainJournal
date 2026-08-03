package com.ecommerce.analytics.infrastructure.messaging;

import com.ecommerce.analytics.application.service.AnalyticsApplicationService;
import com.ecommerce.analytics.infrastructure.config.AnalyticsEventConsumerProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalyticsDomainEventConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void parsesExtendedCompletedSnapshotsAndLegacyMissingRevenueHonestly() {
        var consumer = consumer();
        var extended = consumer.parse(message("""
                {
                  "eventId": "event-extended",
                  "eventType": "OrderCompleted",
                  "aggregateType": "TradeOrder",
                  "aggregateId": "ORD-001",
                  "aggregateVersion": 4,
                  "occurredAt": "2026-07-24T02:00:00Z",
                  "producer": "trade-service",
                  "payloadVersion": 1,
                  "payload": {
                    "paymentNo": "PAY-001",
                    "orderNo": "ORD-001",
                    "userId": 1001,
                    "totalAmount": 70.00,
                    "items": [
                      {
                        "lineNo": 1,
                        "productId": 101,
                        "skuId": 1001,
                        "productTitle": "棉麻收纳袋",
                        "skuCode": "SKU-101",
                        "quantity": 2,
                        "payableAmount": 40.00
                      }
                    ]
                  }
                }
                """));
        assertThat(extended.productLines()).singleElement().satisfies(line ->
                assertThat(line.payableAmount()).isEqualByComparingTo("40.00"));
        assertThat(extended.fingerprint()).hasSize(64);

        var legacy = consumer.parse(message("""
                {
                  "eventId": "event-legacy",
                  "eventType": "OrderCompleted",
                  "aggregateType": "TradeOrder",
                  "aggregateId": "ORD-002",
                  "aggregateVersion": 4,
                  "occurredAt": "2026-07-24T02:00:00Z",
                  "producer": "trade-service",
                  "payloadVersion": 1,
                  "payload": {
                    "orderNo": "ORD-002",
                    "userId": 1001,
                    "totalAmount": 25.00,
                    "items": [
                      {
                        "lineNo": 1,
                        "productId": 102,
                        "skuId": 1002,
                        "productTitle": "旧事件商品",
                        "skuCode": "SKU-102",
                        "quantity": 1
                      }
                    ]
                  }
                }
                """));
        assertThat(legacy.productLines()).singleElement().satisfies(line ->
                assertThat(line.payableAmount()).isNull());
    }

    @Test
    void rejectsWrongSourceIdentityAndUnsupportedPayloadVersion() {
        var consumer = consumer();
        assertThatThrownBy(() -> consumer.parse(message("""
                {
                  "eventId": "event-invalid",
                  "eventType": "PaymentSucceeded",
                  "aggregateType": "PaymentOrder",
                  "aggregateId": "PAY-001",
                  "aggregateVersion": 1,
                  "occurredAt": "2026-07-24T02:00:00Z",
                  "producer": "trade-service",
                  "payloadVersion": 1,
                  "payload": {
                    "paymentNo": "PAY-001",
                    "orderNo": "ORD-001",
                    "userId": 1001,
                    "amount": 70.00
                  }
                }
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source identity");
        assertThatThrownBy(() -> consumer.parse(message("""
                {
                  "eventId": "event-wrong-aggregate-type",
                  "eventType": "PaymentSucceeded",
                  "aggregateType": "TradeOrder",
                  "aggregateId": "PAY-001",
                  "aggregateVersion": 1,
                  "occurredAt": "2026-07-24T02:00:00Z",
                  "producer": "payment-service",
                  "payloadVersion": 1,
                  "payload": {
                    "paymentNo": "PAY-001",
                    "orderNo": "ORD-001",
                    "userId": 1001,
                    "amount": 70.00
                  }
                }
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source identity");
        assertThatThrownBy(() -> consumer.parse(message("""
                {
                  "eventId": "event-wrong-aggregate-id",
                  "eventType": "AfterSaleApplied",
                  "aggregateType": "AfterSaleOrder",
                  "aggregateId": "AS-OTHER",
                  "aggregateVersion": 0,
                  "occurredAt": "2026-07-24T02:00:00Z",
                  "producer": "trade-service",
                  "payloadVersion": 1,
                  "payload": {
                    "afterSaleNo": "AS-001",
                    "orderNo": "ORD-001",
                    "userId": 1001,
                    "refundAmount": 70.00
                  }
                }
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source identity");
        assertThatThrownBy(() -> consumer.parse(message("""
                {
                  "eventId": "event-v2",
                  "eventType": "OrderCreated",
                  "payloadVersion": 2
                }
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload version");
    }

    private AnalyticsDomainEventConsumer consumer() {
        return new AnalyticsDomainEventConsumer(
                new AnalyticsEventConsumerProperties(
                        false,
                        "127.0.0.1:18082",
                        "analytics-test",
                        "ecommerce-order-events",
                        "ecommerce-payment-events",
                        0,
                        500,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(20),
                        20),
                mock(AnalyticsApplicationService.class),
                mock(AnalyticsConsumerFailureRecorder.class),
                objectMapper);
    }

    private MessageView message(String body) {
        MessageView message = mock(MessageView.class);
        when(message.getBody()).thenReturn(ByteBuffer.wrap(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return message;
    }
}
