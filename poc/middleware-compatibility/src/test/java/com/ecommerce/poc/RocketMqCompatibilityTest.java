package com.ecommerce.poc;

import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RocketMqCompatibilityTest extends BaseCompatibilityTest {

    @Value("${poc.rocketmq.endpoints}")
    private String endpoints;

    @Value("${poc.rocketmq.topic}")
    private String topic;

    @Test
    void sendsAndConsumesThroughTheGrpcProxy() throws Exception {
        ClientServiceProvider provider = ClientServiceProvider.loadService();
        ClientConfiguration configuration = ClientConfiguration.newBuilder()
                .setEndpoints(endpoints)
                .enableSsl(false)
                .build();
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String consumerGroup = "poc-middleware-compatibility";
        String tag = "poc" + suffix;
        byte[] payload = ("rocketmq-compatible-" + suffix).getBytes(StandardCharsets.UTF_8);
        FilterExpression expression = new FilterExpression(tag, FilterExpressionType.TAG);

        try (SimpleConsumer consumer = provider.newSimpleConsumerBuilder()
                .setClientConfiguration(configuration)
                .setConsumerGroup(consumerGroup)
                .setAwaitDuration(Duration.ofSeconds(5))
                .setSubscriptionExpressions(Map.of(topic, expression))
                .build();
             Producer producer = provider.newProducerBuilder()
                     .setClientConfiguration(configuration)
                     .setTopics(topic)
                     .build()) {

            Message message = provider.newMessageBuilder()
                    .setTopic(topic)
                    .setTag(tag)
                    .setKeys(suffix)
                    .setBody(payload)
                    .build();
            SendReceipt receipt = producer.send(message);
            assertThat(receipt.getMessageId()).isNotNull();

            MessageView received = receiveOne(consumer);
            assertThat(readBody(received.getBody())).isEqualTo(payload);
            consumer.ack(received);
        }
    }

    private MessageView receiveOne(SimpleConsumer consumer) throws Exception {
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            List<MessageView> messages = consumer.receive(1, Duration.ofSeconds(10));
            if (!messages.isEmpty()) {
                return messages.get(0);
            }
        }
        throw new AssertionError("No RocketMQ message was received before the deadline");
    }

    private byte[] readBody(ByteBuffer source) {
        ByteBuffer copy = source.asReadOnlyBuffer();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return bytes;
    }
}
