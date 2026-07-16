package com.ecommerce.poc;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationContextCompatibilityTest extends BaseCompatibilityTest {

    @Test
    void loadsSpringBootAndCloudContext(ApplicationContext context) {
        assertThat(context).isNotNull();
        assertThat(context.containsBean("dataSource")).isTrue();
        assertThat(context.containsBean("redisTemplate")).isTrue();
    }
}

