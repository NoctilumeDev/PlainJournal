package com.ecommerce.poc;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RedisCompatibilityTest extends BaseCompatibilityTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void writesReadsAndExpiresAValue() {
        String key = "poc:compatibility:" + UUID.randomUUID();
        try {
            redisTemplate.opsForValue().set(key, "ready", Duration.ofSeconds(30));
            assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("ready");
            assertThat(redisTemplate.getExpire(key)).isPositive();
        }
        finally {
            redisTemplate.delete(key);
        }
    }
}

