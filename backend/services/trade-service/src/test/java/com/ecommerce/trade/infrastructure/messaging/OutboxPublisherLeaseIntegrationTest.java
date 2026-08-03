package com.ecommerce.trade.infrastructure.messaging;

import com.ecommerce.trade.application.port.CatalogPort;
import com.ecommerce.trade.application.port.DomainEventPublisher;
import com.ecommerce.trade.application.port.InventoryPort;
import com.ecommerce.trade.infrastructure.persistence.mapper.OutboxEventMapper;
import com.ecommerce.trade.infrastructure.sharding.TradeShardRouter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
class OutboxPublisherLeaseIntegrationTest {

    private static final String AGGREGATE_TYPE = "M3OutboxLeaseTest";
    private static final Instant BASE_TIME = Instant.parse("2026-07-20T00:00:00Z");

    private final OutboxEventMapper outboxMapper;
    private final OutboxClaimService outboxClaimService;
    private final JdbcTemplate jdbcTemplate;
    private final TradeShardRouter shardRouter;

    @MockitoBean
    CatalogPort catalogPort;

    @MockitoBean
    InventoryPort inventoryPort;

    @Autowired
    OutboxPublisherLeaseIntegrationTest(
            OutboxEventMapper outboxMapper,
            OutboxClaimService outboxClaimService,
            JdbcTemplate jdbcTemplate,
            TradeShardRouter shardRouter) {
        this.outboxMapper = outboxMapper;
        this.outboxClaimService = outboxClaimService;
        this.jdbcTemplate = jdbcTemplate;
        this.shardRouter = shardRouter;
    }

    @AfterEach
    void cleanData() {
        jdbcTemplate.update("DELETE FROM outbox_event WHERE aggregate_type = ?", AGGREGATE_TYPE);
    }

    @Test
    void threePublishersCompeteWithoutDuplicateSendAndPreserveAggregateOrder() throws Exception {
        List<String> aggregateIds = List.of(
                "m3-order-" + UUID.randomUUID(),
                "m3-order-" + UUID.randomUUID(),
                "m3-order-" + UUID.randomUUID());
        aggregateIds.forEach(aggregateId -> {
            insertEvent(aggregateId, 1, BASE_TIME.plusSeconds(1));
            insertEvent(aggregateId, 2, BASE_TIME.plusSeconds(2));
            insertEvent(aggregateId, 3, BASE_TIME.plusSeconds(3));
        });

        RecordingPublisher publisher = new RecordingPublisher();
        Clock clock = Clock.fixed(BASE_TIME.plusSeconds(10), ZoneOffset.UTC);
        List<OutboxPublisherJob> jobs = List.of(
                job("publisher-a", publisher, clock),
                job("publisher-b", publisher, clock),
                job("publisher-c", publisher, clock));
        ExecutorService competitors = Executors.newFixedThreadPool(3);
        try {
            for (int round = 0; round < 5 && publishedCount() < 9; round++) {
                List<Future<?>> results = new ArrayList<>();
                for (OutboxPublisherJob job : jobs) {
                    results.add(competitors.submit(job::publishPendingEvents));
                }
                for (Future<?> result : results) {
                    result.get();
                }
            }
        } finally {
            competitors.shutdownNow();
            jobs.forEach(OutboxPublisherJob::close);
        }

        assertThat(publishedCount()).isEqualTo(9);
        assertThat(publisher.totalCalls()).isEqualTo(9);
        assertThat(publisher.duplicateEventIds()).isEmpty();
        for (String aggregateId : aggregateIds) {
            assertThat(publisher.versionsFor(aggregateId)).containsExactly(1, 2, 3);
        }
    }

    @Test
    void expiredOwnerIsFencedAndAnotherPublisherRecoversTheEvent() {
        String aggregateId = "m3-recovery-" + UUID.randomUUID();
        String eventId = insertEvent(aggregateId, 1, BASE_TIME);
        Instant claimedAt = BASE_TIME.plusSeconds(1);
        Instant expiredAt = BASE_TIME.plusSeconds(2);

        assertThat(outboxMapper.claim(eventId, "dead-publisher", claimedAt, expiredAt)).isEqualTo(1);
        assertThat(outboxMapper.markPublished(
                eventId, "dead-publisher", expiredAt.plusMillis(1))).isZero();
        assertThat(outboxMapper.markFailed(
                eventId,
                "dead-publisher",
                expiredAt.plusSeconds(1),
                "late failure",
                expiredAt.plusMillis(1))).isZero();

        RecordingPublisher publisher = new RecordingPublisher();
        OutboxPublisherJob recoveringJob = job(
                "recovery-publisher",
                publisher,
                Clock.fixed(expiredAt.plusSeconds(1), ZoneOffset.UTC));
        try {
            recoveringJob.publishPendingEvents();
        } finally {
            recoveringJob.close();
        }

        Map<String, Object> state = jdbcTemplate.queryForMap(
                "SELECT status, attempts, claim_owner, claim_until FROM outbox_event WHERE id = ?",
                eventId);
        assertThat(state.get("status")).isEqualTo("PUBLISHED");
        assertThat(((Number) state.get("attempts")).intValue()).isZero();
        assertThat(state.get("claim_owner")).isNull();
        assertThat(state.get("claim_until")).isNull();
        assertThat(publisher.totalCalls()).isEqualTo(1);
    }

    private OutboxPublisherJob job(String owner, DomainEventPublisher publisher, Clock clock) {
        OutboxProperties properties = new OutboxProperties(
                true,
                "127.0.0.1:18082",
                "ecommerce-order-events",
                "ecommerce-flash-sale-events",
                2000,
                Duration.ZERO,
                100,
                2,
                owner,
                Duration.ofSeconds(30),
                Duration.ofSeconds(1));
        return new OutboxPublisherJob(
                outboxMapper,
                outboxClaimService,
                publisher,
                properties,
                new ProcessTerminationFaultInjector(ProcessTerminationFaultProperties.disabled()),
                clock,
                new SimpleMeterRegistry(),
                shardRouter);
    }

    private String insertEvent(String aggregateId, int version, Instant createdAt) {
        String eventId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO outbox_event
                    (id, event_type, aggregate_type, aggregate_id, aggregate_version, payload,
                     status, attempts, next_attempt_at, claimed_at, claim_owner, claim_until,
                     published_at, last_error, created_at, updated_at)
                VALUES
                    (?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, NULL, NULL, NULL, NULL, NULL, ?, ?)
                """,
                eventId,
                "M3OutboxEvent",
                AGGREGATE_TYPE,
                aggregateId,
                version,
                "{\"aggregateId\":\"" + aggregateId + "\",\"version\":" + version + "}",
                createdAt,
                createdAt,
                createdAt);
        return eventId;
    }

    private int publishedCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE aggregate_type = ? AND status = 'PUBLISHED'",
                Integer.class,
                AGGREGATE_TYPE);
    }

    private static final class RecordingPublisher implements DomainEventPublisher {

        private final Map<String, AtomicInteger> callsByEvent = new ConcurrentHashMap<>();
        private final Map<String, List<Integer>> versionsByAggregate = new ConcurrentHashMap<>();

        @Override
        public void publish(String eventId, String eventType, String payload) {
            callsByEvent.computeIfAbsent(eventId, ignored -> new AtomicInteger()).incrementAndGet();
            String aggregateId = jsonString(payload, "aggregateId");
            int version = Integer.parseInt(jsonNumber(payload, "version"));
            versionsByAggregate.computeIfAbsent(
                    aggregateId,
                    ignored -> Collections.synchronizedList(new ArrayList<>())).add(version);
        }

        int totalCalls() {
            return callsByEvent.values().stream().mapToInt(AtomicInteger::get).sum();
        }

        List<String> duplicateEventIds() {
            return callsByEvent.entrySet().stream()
                    .filter(entry -> entry.getValue().get() > 1)
                    .map(Map.Entry::getKey)
                    .toList();
        }

        List<Integer> versionsFor(String aggregateId) {
            return versionsByAggregate.getOrDefault(aggregateId, List.of());
        }

        private String jsonString(String json, String field) {
            String prefix = "\"" + field + "\":\"";
            int start = json.indexOf(prefix) + prefix.length();
            return json.substring(start, json.indexOf('"', start));
        }

        private String jsonNumber(String json, String field) {
            String prefix = "\"" + field + "\":";
            int start = json.indexOf(prefix) + prefix.length();
            int end = json.indexOf('}', start);
            return json.substring(start, end);
        }
    }
}
