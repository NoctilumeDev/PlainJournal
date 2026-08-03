package com.ecommerce.platform.common.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeysetCursorTest {

    @Test
    void roundTripsTimestampAndLargeIdentifier() {
        KeysetCursor cursor = new KeysetCursor(
                Instant.parse("2026-07-22T10:15:30.123Z"),
                7_440_000_000_000_000_001L);

        KeysetCursor decoded = KeysetCursor.decode(cursor.encode());

        assertThat(decoded).isEqualTo(cursor);
    }

    @Test
    void rejectsMalformedOrUnsupportedPayloads() {
        assertThatThrownBy(() -> KeysetCursor.decode("not-a-cursor"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KeysetCursor.decode(""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
