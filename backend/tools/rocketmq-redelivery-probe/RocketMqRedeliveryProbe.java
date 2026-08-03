import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.Producer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class RocketMqRedeliveryProbe {

    private RocketMqRedeliveryProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "Expected endpoints, topic, consumerGroup and timeoutSeconds");
        }
        String endpoints = args[0];
        String topic = args[1];
        String consumerGroup = args[2];
        long timeoutSeconds = Long.parseLong(args[3]);
        String marker = "plainjournal-redelivery-" + UUID.randomUUID();

        ClientServiceProvider provider = ClientServiceProvider.loadService();
        ClientConfiguration configuration = ClientConfiguration.newBuilder()
                .setEndpoints(endpoints)
                .enableSsl(false)
                .build();
        try (SimpleConsumer consumer = provider.newSimpleConsumerBuilder()
                .setClientConfiguration(configuration)
                .setConsumerGroup(consumerGroup)
                .setAwaitDuration(Duration.ofSeconds(3))
                .setSubscriptionExpressions(Map.of(
                        topic,
                        new FilterExpression("REDELIVERY_PROBE", FilterExpressionType.TAG)))
                .build();
                Producer producer = provider.newProducerBuilder()
                        .setClientConfiguration(configuration)
                        .setTopics(topic)
                        .build()) {
            Message outbound = provider.newMessageBuilder()
                    .setTopic(topic)
                    .setTag("REDELIVERY_PROBE")
                    .setKeys(marker)
                    .setBody(marker.getBytes(StandardCharsets.UTF_8))
                    .build();
            producer.send(outbound);

            MessageView first = receiveExpected(
                    consumer,
                    marker,
                    Instant.now().plusSeconds(30));
            String firstMessageId = first.getMessageId().toString();
            int firstDeliveryAttempt = first.getDeliveryAttempt();
            System.out.printf(
                    "FIRST_RECEIVE|messageId=%s|deliveryAttempt=%d|receivedAt=%s%n",
                    firstMessageId,
                    firstDeliveryAttempt,
                    Instant.now());

            Instant deadline = Instant.now().plusSeconds(timeoutSeconds);
            while (Instant.now().isBefore(deadline)) {
                for (MessageView candidate : consumer.receive(
                        1,
                        Duration.ofSeconds(10))) {
                    String body = body(candidate);
                    if (!marker.equals(body)) {
                        consumer.ack(candidate);
                        System.out.printf(
                                "UNEXPECTED_MESSAGE_ACKED|messageId=%s|body=%s%n",
                                candidate.getMessageId(),
                                body);
                        continue;
                    }
                    String secondMessageId = candidate.getMessageId().toString();
                    int secondDeliveryAttempt = candidate.getDeliveryAttempt();
                    consumer.ack(candidate);
                    boolean sameMessage = firstMessageId.equals(secondMessageId);
                    boolean attemptAdvanced = secondDeliveryAttempt > firstDeliveryAttempt;
                    System.out.printf(
                            "SECOND_RECEIVE|messageId=%s|deliveryAttempt=%d|sameMessage=%s"
                                    + "|attemptAdvanced=%s|receivedAt=%s%n",
                            secondMessageId,
                            secondDeliveryAttempt,
                            sameMessage,
                            attemptAdvanced,
                            Instant.now());
                    if (!sameMessage || !attemptAdvanced) {
                        System.exit(3);
                    }
                    System.out.printf(
                            "PROBE_RESULT|redelivered=true|messageId=%s|firstAttempt=%d"
                                    + "|secondAttempt=%d%n",
                            firstMessageId,
                            firstDeliveryAttempt,
                            secondDeliveryAttempt);
                    return;
                }
            }
            System.out.printf(
                    "PROBE_RESULT|redelivered=false|messageId=%s|firstAttempt=%d"
                            + "|timeoutSeconds=%d%n",
                    firstMessageId,
                    firstDeliveryAttempt,
                    timeoutSeconds);
            System.exit(2);
        }
    }

    private static MessageView receiveExpected(
            SimpleConsumer consumer,
            String marker,
            Instant deadline) throws Exception {
        while (Instant.now().isBefore(deadline)) {
            List<MessageView> messages = consumer.receive(
                    1,
                    Duration.ofSeconds(10));
            for (MessageView message : messages) {
                String body = body(message);
                if (marker.equals(body)) {
                    return message;
                }
                consumer.ack(message);
                System.out.printf(
                        "UNEXPECTED_MESSAGE_ACKED|messageId=%s|body=%s%n",
                        message.getMessageId(),
                        body);
            }
        }
        throw new IllegalStateException("Probe message was not received before the initial deadline");
    }

    private static String body(MessageView message) {
        var source = message.getBody().asReadOnlyBuffer();
        byte[] bytes = new byte[source.remaining()];
        source.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
