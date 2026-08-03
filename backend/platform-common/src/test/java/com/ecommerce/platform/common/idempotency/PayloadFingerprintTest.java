package com.ecommerce.platform.common.idempotency;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PayloadFingerprintTest {

    @Test
    void isStableAndSeparatesComponentBoundaries() {
        String first = PayloadFingerprint.of("ab", "c", 1L);
        String replay = PayloadFingerprint.of("ab", "c", 1L);
        String differentBoundary = PayloadFingerprint.of("a", "bc", 1L);
        String differentType = PayloadFingerprint.of("ab", "c", "1");

        assertThat(first).hasSize(64).isEqualTo(replay);
        assertThat(first).isNotEqualTo(differentBoundary).isNotEqualTo(differentType);
        assertThat(PayloadFingerprint.matches(first, replay)).isTrue();
        assertThat(PayloadFingerprint.matches(null, replay)).isFalse();
    }
}
