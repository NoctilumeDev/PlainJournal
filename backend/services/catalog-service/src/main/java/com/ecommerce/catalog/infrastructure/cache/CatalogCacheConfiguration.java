package com.ecommerce.catalog.infrastructure.cache;

import com.ecommerce.catalog.application.port.ProductDetailCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableConfigurationProperties(CatalogCacheProperties.class)
public class CatalogCacheConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "ecommerce.catalog.cache",
            name = "enabled",
            havingValue = "false")
    public ProductDetailCache bypassProductDetailCache() {
        return new BypassProductDetailCache();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(
            prefix = "ecommerce.catalog.cache",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public TwoLevelProductDetailCache productDetailCache(
            CatalogCacheProperties properties,
            CatalogCacheStore store,
            ObjectMapper objectMapper,
            Clock clock,
            MeterRegistry meterRegistry,
            PlatformTransactionManager transactionManager) {
        ThreadPoolExecutor refreshExecutor = new ThreadPoolExecutor(
                properties.refreshThreads(),
                properties.refreshThreads(),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.refreshQueueCapacity()),
                runnable -> {
                    Thread thread = new Thread(runnable, "catalog-cache-refresh");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        TransactionTemplate readTransaction = new TransactionTemplate(transactionManager);
        readTransaction.setReadOnly(true);
        return new TwoLevelProductDetailCache(
                properties,
                store,
                objectMapper,
                clock,
                meterRegistry,
                refreshExecutor,
                readTransaction);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "ecommerce.catalog.cache",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public CatalogCacheStore catalogCacheStore(StringRedisTemplate redisTemplate) {
        return new RedisCatalogCacheStore(redisTemplate);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "ecommerce.catalog.cache",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public RedisMessageListenerContainer catalogCacheInvalidationListener(
            RedisConnectionFactory connectionFactory,
            CatalogCacheProperties properties,
            ProductDetailCache cache) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        org.springframework.data.redis.connection.MessageListener listener =
                (message, pattern) -> {
                    String value = new String(message.getBody(), StandardCharsets.UTF_8);
                    try {
                        cache.receiveInvalidation(Long.valueOf(value));
                    } catch (NumberFormatException ignored) {
                        // Ignore malformed external invalidation messages.
                    }
                };
        container.addMessageListener(listener, new ChannelTopic(properties.invalidationChannel()));
        return container;
    }
}
