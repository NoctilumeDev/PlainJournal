package com.ecommerce.chat.infrastructure.realtime;

import com.ecommerce.chat.application.exception.ChatError;
import com.ecommerce.chat.application.exception.ChatException;
import com.ecommerce.chat.application.model.ChatModels.WebSocketTicketIdentity;
import com.ecommerce.chat.application.model.ChatModels.WebSocketTicketView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatWebSocketTicketServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-23T08:00:00Z");
    private static final Duration TTL = Duration.ofSeconds(30);

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final SecureRandom secureRandom = mock(SecureRandom.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private ChatWebSocketTicketService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doAnswer(invocation -> {
            byte[] bytes = invocation.getArgument(0);
            for (int index = 0; index < bytes.length; index++) {
                bytes[index] = (byte) (index + 1);
            }
            return null;
        }).when(secureRandom).nextBytes(any(byte[].class));
        service = new ChatWebSocketTicketService(
                redisTemplate,
                properties(),
                objectMapper,
                secureRandom,
                clock);
    }

    @Test
    void issuesOpaqueTicketAndStoresOnlyItsDigestWithTtl() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), eq(TTL)))
                .thenReturn(true);

        WebSocketTicketView view = service.issue(
                9301L,
                List.of("ROLE_CUSTOMER", "ROLE_CUSTOMER", "ROLE_METRICS"));

        assertThat(view.ticket()).matches("[A-Za-z0-9_-]{43}");
        assertThat(view.targetPath()).isEqualTo("/ws/chat");
        assertThat(view.queryParameter()).isEqualTo("ticket");
        assertThat(view.expiresAt()).isEqualTo(NOW.plus(TTL));
        verify(valueOperations).setIfAbsent(
                eq("ecommerce:test:chat:ws-ticket:"
                        + "eb9f16800c9029ffca85695763d23c3ace71011cf40e9354acd810205e250f87"),
                anyString(),
                eq(TTL));
    }

    @Test
    void atomicallyConsumesOneTicketAcrossConcurrentAttempts() throws Exception {
        String payload = objectMapper.writeValueAsString(new StoredTicketFixture(
                "test",
                9301L,
                List.of("ROLE_CUSTOMER"),
                "/ws/chat",
                NOW,
                NOW.plus(TTL)));
        AtomicReference<String> stored = new AtomicReference<>(payload);
        when(redisTemplate.execute(
                ArgumentMatchers.<RedisScript<String>>any(),
                anyList()))
                .thenAnswer(invocation -> stored.getAndSet(null));

        List<Optional<WebSocketTicketIdentity>> results = runConcurrently(
                16,
                () -> service.consume(
                        "abcdefghijklmnopqrstuvwxyzABCDEFGH123456789",
                        "/ws/chat"));

        assertThat(results.stream().filter(Optional::isPresent).count()).isEqualTo(1L);
        assertThat(results.stream()
                .flatMap(Optional::stream)
                .map(WebSocketTicketIdentity::userId))
                .containsExactly(9301L);
    }

    @Test
    void rejectsWrongPathWithoutConsumingTicket() {
        Optional<WebSocketTicketIdentity> result = service.consume(
                "abcdefghijklmnopqrstuvwxyzABCDEFGH123456789",
                "/ws/other");

        assertThat(result).isEmpty();
        verify(redisTemplate, never()).execute(
                ArgumentMatchers.<RedisScript<String>>any(),
                anyList());
    }

    @Test
    void treatsAtomicRedisTtlAsAuthoritativeDespiteJvmClockSkew() throws Exception {
        String payload = objectMapper.writeValueAsString(new StoredTicketFixture(
                "test",
                9301L,
                List.of("ROLE_CUSTOMER"),
                "/ws/chat",
                NOW.minusSeconds(60),
                NOW.minusSeconds(30)));
        when(redisTemplate.execute(
                ArgumentMatchers.<RedisScript<String>>any(),
                anyList()))
                .thenReturn(payload);

        assertThat(service.consume(
                "abcdefghijklmnopqrstuvwxyzABCDEFGH123456789",
                "/ws/chat"))
                .contains(new WebSocketTicketIdentity(
                        9301L,
                        List.of("ROLE_CUSTOMER")));
    }

    @Test
    void failsClosedWhenRedisCannotIssueOrConsume() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), eq(TTL)))
                .thenThrow(new RedisConnectionFailureException("test outage"));

        assertThatThrownBy(() -> service.issue(9301L, List.of("ROLE_CUSTOMER")))
                .isInstanceOf(ChatException.class)
                .extracting(exception -> ((ChatException) exception).error())
                .isEqualTo(ChatError.CHAT_REALTIME_UNAVAILABLE);

        when(redisTemplate.execute(
                ArgumentMatchers.<RedisScript<String>>any(),
                anyList()))
                .thenThrow(new RedisConnectionFailureException("test outage"));
        assertThatThrownBy(() -> service.consume(
                "abcdefghijklmnopqrstuvwxyzABCDEFGH123456789",
                "/ws/chat"))
                .isInstanceOf(ChatException.class)
                .extracting(exception -> ((ChatException) exception).error())
                .isEqualTo(ChatError.CHAT_REALTIME_UNAVAILABLE);
    }

    private ChatWebSocketTicketProperties properties() {
        return new ChatWebSocketTicketProperties(
                true,
                "test",
                TTL,
                "/ws/chat",
                32);
    }

    private <T> List<T> runConcurrently(int participants, Callable<T> action) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(participants);
        CountDownLatch ready = new CountDownLatch(participants);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int index = 0; index < participants; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent ticket test start timed out");
                    }
                    return action.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            return List.copyOf(results);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private record StoredTicketFixture(
            String namespace,
            Long userId,
            List<String> roles,
            String targetPath,
            Instant issuedAt,
            Instant expiresAt
    ) {
    }
}
