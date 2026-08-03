package com.ecommerce.trade.application.service;

import com.ecommerce.trade.infrastructure.persistence.entity.ReconciliationRecordEntity;
import com.ecommerce.trade.infrastructure.persistence.mapper.ReconciliationRecordMapper;
import com.ecommerce.trade.infrastructure.reconciliation.TradeReconciliationProperties;
import com.ecommerce.trade.infrastructure.sharding.TradeShardRouter;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TradeReconciliationServiceShardRoutingTest {

    @Test
    void readsAndCountsIssuesThroughExplicitShardRoutes() {
        ReconciliationRecordMapper mapper = mock(ReconciliationRecordMapper.class);
        RecordingShardRouter router = new RecordingShardRouter(2);
        when(mapper.selectByStatus("OPEN", 2)).thenAnswer(ignored -> switch (router.currentShard()) {
            case 0 -> List.of(
                    issue(100L, "ORDER-100", Instant.parse("2026-07-25T10:00:00Z")),
                    issue(80L, "ORDER-080", Instant.parse("2026-07-25T08:00:00Z")));
            case 1 -> List.of(
                    issue(90L, "ORDER-090", Instant.parse("2026-07-25T09:00:00Z")),
                    issue(70L, "ORDER-070", Instant.parse("2026-07-25T07:00:00Z")));
            default -> throw new AssertionError("Mapper access was not shard-routed");
        });
        when(mapper.countOpen()).thenAnswer(ignored -> switch (router.currentShard()) {
            case 0 -> 2L;
            case 1 -> 3L;
            default -> throw new AssertionError("Metric access was not shard-routed");
        });

        TradeReconciliationService service = new TradeReconciliationService(
                mapper,
                mock(TradeReconciliationProperties.class),
                mock(TransactionTemplate.class),
                router);

        assertThat(service.listIssues("OPEN", 2))
                .extracting(TradeReconciliationService.ReconciliationIssueView::referenceNo)
                .containsExactly("ORDER-100", "ORDER-090");
        assertThat(router.visitedShards()).containsExactly(0, 1);

        router.clearVisits();
        assertThat(service.countOpenIssues()).isEqualTo(5);
        assertThat(router.visitedShards()).containsExactly(0, 1);
    }

    private static ReconciliationRecordEntity issue(
            long id,
            String referenceNo,
            Instant detectedAt) {
        ReconciliationRecordEntity issue = new ReconciliationRecordEntity();
        issue.setId(id);
        issue.setDomain("ORDER");
        issue.setReferenceNo(referenceNo);
        issue.setIssueType("ORDER_STATE_EVENT_MISSING");
        issue.setStatus("OPEN");
        issue.setOccurrences(1);
        issue.setFirstDetectedAt(detectedAt);
        issue.setLastDetectedAt(detectedAt);
        return issue;
    }

    private static final class RecordingShardRouter implements TradeShardRouter {

        private final int shardCount;
        private final List<Integer> visited = new ArrayList<>();
        private int currentShard = -1;

        private RecordingShardRouter(int shardCount) {
            this.shardCount = shardCount;
        }

        @Override
        public boolean isRouted() {
            return currentShard >= 0;
        }

        @Override
        public int shardCount() {
            return shardCount;
        }

        @Override
        public int shardIndex(long userId) {
            return Math.floorMod(userId, shardCount);
        }

        @Override
        public <T> T executeForUser(long userId, Supplier<T> action) {
            return executeOnShard(shardIndex(userId), action);
        }

        @Override
        public <T> T executeOnShard(int shardIndex, Supplier<T> action) {
            if (currentShard >= 0) {
                if (currentShard != shardIndex) {
                    throw new IllegalStateException("Cannot switch shards inside a routed action");
                }
                return action.get();
            }
            currentShard = shardIndex;
            visited.add(shardIndex);
            try {
                return action.get();
            } finally {
                currentShard = -1;
            }
        }

        int currentShard() {
            return currentShard;
        }

        List<Integer> visitedShards() {
            return List.copyOf(visited);
        }

        void clearVisits() {
            visited.clear();
        }
    }
}
