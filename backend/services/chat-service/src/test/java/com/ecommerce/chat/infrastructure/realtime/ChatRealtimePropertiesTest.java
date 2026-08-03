package com.ecommerce.chat.infrastructure.realtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ChatRealtimePropertiesTest {

    @Test
    void defaultsBrowserOriginsAndIncludesEnvironmentNamespaceInRedisKeys() {
        ChatRealtimeProperties properties = new ChatRealtimeProperties(
                true,
                null,
                "m8-test",
                "chat-node-a",
                "127.0.0.1:18082",
                "ecommerce-chat-events",
                "ecommerce-chat-delivery-events",
                "ecommerce-chat-dispatcher",
                "ecommerce-chat-delivery",
                0,
                500,
                Duration.ofSeconds(5),
                Duration.ofSeconds(15),
                20,
                Duration.ofSeconds(12),
                Duration.ofSeconds(4),
                100);

        assertThat(properties.redisKeyPrefix()).isEqualTo("ecommerce:m8-test:chat:");
        assertThat(properties.invisibleDuration()).isEqualTo(Duration.ofSeconds(15));
        assertThat(properties.allowedOrigins()).containsExactly(
                "http://127.0.0.1:18200",
                "http://127.0.0.1:18201",
                "http://localhost:18200",
                "http://localhost:18201");
    }

    @Test
    void rejectsWildcardOrPathBasedBrowserOrigins() {
        assertThatIllegalArgumentException().isThrownBy(() -> properties(
                List.of("https://*.example.com")));
        assertThatIllegalArgumentException().isThrownBy(() -> properties(
                List.of("https://shop.example.com/chat")));
    }

    private ChatRealtimeProperties properties(List<String> origins) {
        return new ChatRealtimeProperties(
                true,
                origins,
                "m8-test",
                "chat-node-a",
                "127.0.0.1:18082",
                "ecommerce-chat-events",
                "ecommerce-chat-delivery-events",
                "ecommerce-chat-dispatcher",
                "ecommerce-chat-delivery",
                0,
                500,
                Duration.ofSeconds(5),
                Duration.ofSeconds(15),
                20,
                Duration.ofSeconds(12),
                Duration.ofSeconds(4),
                100);
    }
}
