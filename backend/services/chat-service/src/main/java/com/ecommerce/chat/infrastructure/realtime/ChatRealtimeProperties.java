package com.ecommerce.chat.infrastructure.realtime;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;
import java.util.List;

@Validated
@ConfigurationProperties("ecommerce.chat.realtime")
public record ChatRealtimeProperties(
        boolean enabled,
        List<String> allowedOrigins,
        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9_-]+")
        String namespace,
        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9_-]+")
        String nodeId,
        @NotBlank String endpoints,
        @NotBlank String sourceTopic,
        @NotBlank String deliveryTopic,
        @NotBlank String dispatcherConsumerGroup,
        @NotBlank String deliveryConsumerGroupPrefix,
        @Min(0) long initialDelay,
        @Min(100) long fixedDelay,
        @NotNull Duration awaitDuration,
        @NotNull Duration invisibleDuration,
        @Min(1) @Max(100) int batchSize,
        @NotNull Duration presenceTtl,
        @NotNull Duration refreshInterval,
        @Min(1) @Max(500) int offlineReplayBatchSize
) {
    private static final List<String> DEFAULT_ALLOWED_ORIGINS = List.of(
            "http://127.0.0.1:18200",
            "http://127.0.0.1:18201",
            "http://localhost:18200",
            "http://localhost:18201");

    public ChatRealtimeProperties {
        allowedOrigins = normalizeAllowedOrigins(allowedOrigins);
        namespace = namespace == null || namespace.isBlank() ? "local" : namespace;
        nodeId = nodeId == null || nodeId.isBlank() ? "chat-local" : nodeId;
        endpoints = endpoints == null || endpoints.isBlank()
                ? "127.0.0.1:18082"
                : endpoints;
        sourceTopic = sourceTopic == null || sourceTopic.isBlank()
                ? "ecommerce-chat-events"
                : sourceTopic;
        deliveryTopic = deliveryTopic == null || deliveryTopic.isBlank()
                ? "ecommerce-chat-delivery-events"
                : deliveryTopic;
        dispatcherConsumerGroup = dispatcherConsumerGroup == null
                || dispatcherConsumerGroup.isBlank()
                ? "ecommerce-chat-dispatcher"
                : dispatcherConsumerGroup;
        deliveryConsumerGroupPrefix = deliveryConsumerGroupPrefix == null
                || deliveryConsumerGroupPrefix.isBlank()
                ? "ecommerce-chat-delivery"
                : deliveryConsumerGroupPrefix;
        fixedDelay = fixedDelay <= 0 ? 500 : fixedDelay;
        awaitDuration = awaitDuration == null ? Duration.ofSeconds(5) : awaitDuration;
        invisibleDuration = invisibleDuration == null ? Duration.ofSeconds(15) : invisibleDuration;
        batchSize = batchSize <= 0 ? 20 : batchSize;
        presenceTtl = presenceTtl == null ? Duration.ofSeconds(12) : presenceTtl;
        refreshInterval = refreshInterval == null ? Duration.ofSeconds(4) : refreshInterval;
        offlineReplayBatchSize = offlineReplayBatchSize <= 0 ? 100 : offlineReplayBatchSize;
        requirePositive(awaitDuration, "awaitDuration");
        requirePositive(invisibleDuration, "invisibleDuration");
        requirePositive(presenceTtl, "presenceTtl");
        requirePositive(refreshInterval, "refreshInterval");
        if (presenceTtl != null && refreshInterval != null
                && presenceTtl.compareTo(refreshInterval.multipliedBy(2)) < 0) {
            throw new IllegalArgumentException("presenceTtl must be at least twice refreshInterval");
        }
    }

    private static List<String> normalizeAllowedOrigins(List<String> configured) {
        List<String> origins = configured == null || configured.isEmpty()
                ? DEFAULT_ALLOWED_ORIGINS
                : configured.stream()
                        .map(String::trim)
                        .filter(value -> !value.isEmpty())
                        .distinct()
                        .toList();
        if (origins.isEmpty()) {
            throw new IllegalArgumentException("allowedOrigins must not be empty");
        }
        for (String origin : origins) {
            URI uri;
            try {
                uri = URI.create(origin);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "allowedOrigins contains an invalid origin", exception);
            }
            boolean supportedScheme = "http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme());
            if (!supportedScheme
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty())
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || origin.contains("*")) {
                throw new IllegalArgumentException(
                        "allowedOrigins must contain exact HTTP(S) origins");
            }
        }
        return List.copyOf(origins);
    }

    public String deliveryConsumerGroup() {
        return deliveryConsumerGroupPrefix + "-" + nodeId;
    }

    public String redisKeyPrefix() {
        return "ecommerce:" + namespace + ":chat:";
    }

    public String nodeTag() {
        return nodeTag(nodeId);
    }

    public String nodeTag(String targetNodeId) {
        if (targetNodeId == null || !targetNodeId.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("targetNodeId contains unsupported characters");
        }
        return "NODE_" + targetNodeId;
    }

    private static void requirePositive(Duration value, String name) {
        if (value != null && (value.isNegative() || value.isZero())) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
    }
}
