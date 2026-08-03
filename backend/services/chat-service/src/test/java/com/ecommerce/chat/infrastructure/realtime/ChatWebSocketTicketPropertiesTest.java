package com.ecommerce.chat.infrastructure.realtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ChatWebSocketTicketPropertiesTest {

    @Test
    void appliesBoundedSecureDefaults() {
        ChatWebSocketTicketProperties properties =
                new ChatWebSocketTicketProperties(true, null, null, null, 0);

        assertThat(properties.namespace()).isEqualTo("local");
        assertThat(properties.ttl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.targetPath()).isEqualTo("/ws/chat");
        assertThat(properties.entropyBytes()).isEqualTo(32);
        assertThat(properties.redisKeyPrefix())
                .isEqualTo("ecommerce:local:chat:ws-ticket:");
    }

    @Test
    void rejectsTicketsThatLiveTooBrieflyOrTooLong() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChatWebSocketTicketProperties(
                        true,
                        "test",
                        Duration.ofSeconds(4),
                        "/ws/chat",
                        32));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ChatWebSocketTicketProperties(
                        true,
                        "test",
                        Duration.ofSeconds(121),
                        "/ws/chat",
                        32));
    }
}
