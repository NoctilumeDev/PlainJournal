package com.ecommerce.trade.infrastructure.messaging;

import com.ecommerce.trade.application.port.DomainEventPublisher;
import com.ecommerce.trade.infrastructure.persistence.entity.OutboxEventEntity;
import com.ecommerce.trade.infrastructure.persistence.mapper.OutboxEventMapper;
import com.ecommerce.trade.infrastructure.sharding.UnshardedTradeShardRouter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OutboxPublisherJobTest {

    @Test
    void conciseErrorIncludesTheDeepestCause() {
        CompletionException exception = new CompletionException(
                new IllegalStateException(
                        "Producer failed",
                        new java.net.ConnectException(
                                "Connection refused: /127.0.0.1:18082")));

        assertThat(OutboxPublisherJob.conciseError(exception))
                .contains("CompletionException")
                .contains("ConnectException: Connection refused: /127.0.0.1:18082");
    }

    @Test
    void publishesDifferentAggregatesConcurrentlyAndPreservesPerAggregateOrder() throws Exception {
        OutboxEventMapper mapper = mock(OutboxEventMapper.class);
        OutboxClaimService claimService = mock(OutboxClaimService.class);
        DomainEventPublisher publisher = mock(DomainEventPublisher.class);
        OutboxEventEntity orderA1 = event("A1", "order-a", Instant.parse("2026-01-01T00:00:00Z"));
        OutboxEventEntity orderA2 = event("A2", "order-a", Instant.parse("2026-01-01T00:00:01Z"));
        OutboxEventEntity orderB1 = event("B1", "order-b", Instant.parse("2026-01-01T00:00:02Z"));
        when(claimService.claimBatch(anyString(), any(), any(Integer.class)))
                .thenReturn(new OutboxClaimService.ClaimBatch(
                        List.of(
                                new OutboxClaimService.ClaimedEvent(0, orderA1),
                                new OutboxClaimService.ClaimedEvent(0, orderA2),
                                new OutboxClaimService.ClaimedEvent(0, orderB1)),
                        0,
                        0));
        when(mapper.markPublished(anyString(), anyString(), any())).thenReturn(1);
        when(mapper.currentTime()).thenReturn(Instant.parse("2026-01-01T00:00:03Z"));

        CountDownLatch orderAStarted = new CountDownLatch(1);
        CountDownLatch orderBStarted = new CountDownLatch(1);
        AtomicBoolean orderA1Completed = new AtomicBoolean();
        List<String> completions = Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> {
            String eventId = invocation.getArgument(0);
            if ("A1".equals(eventId)) {
                orderAStarted.countDown();
                assertThat(orderBStarted.await(2, TimeUnit.SECONDS)).isTrue();
                completions.add(eventId);
                orderA1Completed.set(true);
            } else if ("B1".equals(eventId)) {
                orderBStarted.countDown();
                assertThat(orderAStarted.await(2, TimeUnit.SECONDS)).isTrue();
                completions.add(eventId);
            } else {
                assertThat(orderA1Completed.get()).isTrue();
                completions.add(eventId);
            }
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }).when(publisher).publishAsync(anyString(), anyString(), anyString());

        OutboxProperties properties = new OutboxProperties(
                true, "127.0.0.1:18082", "ecommerce-order-events",
                "ecommerce-flash-sale-events", 2000,
                Duration.ZERO, 100, 2, "publisher-a", Duration.ofSeconds(30),
                Duration.ofSeconds(1));
        OutboxPublisherJob job = new OutboxPublisherJob(
                mapper, claimService, publisher, properties,
                new ProcessTerminationFaultInjector(ProcessTerminationFaultProperties.disabled()),
                Clock.systemUTC(), new SimpleMeterRegistry(), new UnshardedTradeShardRouter());
        try {
            assertThatNoException().isThrownBy(job::publishPendingEvents);
        } finally {
            job.close();
        }

        assertThat(completions).containsExactlyInAnyOrder("A1", "A2", "B1");
        assertThat(completions.indexOf("A1")).isLessThan(completions.indexOf("A2"));
    }

    @Test
    void observesTransientClaimConflictAndLetsTheNextScheduleRetry() {
        OutboxEventMapper mapper = mock(OutboxEventMapper.class);
        OutboxClaimService claimService = mock(OutboxClaimService.class);
        DomainEventPublisher publisher = mock(DomainEventPublisher.class);
        when(claimService.claimBatch(anyString(), any(), any(Integer.class)))
                .thenThrow(new CannotAcquireLockException("simulated deadlock"));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        OutboxProperties properties = new OutboxProperties(
                true, "127.0.0.1:18082", "ecommerce-order-events",
                "ecommerce-flash-sale-events", 2000,
                Duration.ZERO, 100, 2, "publisher-a", Duration.ofSeconds(30),
                Duration.ofSeconds(1));
        OutboxPublisherJob job = new OutboxPublisherJob(
                mapper, claimService, publisher, properties,
                new ProcessTerminationFaultInjector(ProcessTerminationFaultProperties.disabled()),
                Clock.systemUTC(), meterRegistry, new UnshardedTradeShardRouter());
        try {
            assertThatNoException().isThrownBy(job::publishPendingEvents);
        } finally {
            job.close();
        }

        verifyNoInteractions(publisher);
        assertThat(meterRegistry.get("ecommerce.outbox.claims")
                .tag("outcome", "contended")
                .counter()
                .count()).isEqualTo(1.0d);
    }

    private OutboxEventEntity event(String id, String aggregateId, Instant createdAt) {
        OutboxEventEntity event = new OutboxEventEntity();
        event.setId(id);
        event.setEventType("TestEvent");
        event.setAggregateType("Order");
        event.setAggregateId(aggregateId);
        event.setPayload("{}");
        event.setCreatedAt(createdAt);
        return event;
    }
}
