package com.ecommerce.trade.infrastructure.messaging;

import com.ecommerce.trade.infrastructure.persistence.entity.OutboxEventEntity;
import com.ecommerce.trade.infrastructure.persistence.mapper.OutboxEventMapper;
import com.ecommerce.trade.infrastructure.sharding.TradeShardRouter;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxClaimServiceTest {

    @Test
    void rotatesTheFirstShardSoAFullBatchCannotStarveAnotherShard() {
        OutboxEventMapper mapper = mock(OutboxEventMapper.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TradeShardRouter shardRouter = mock(TradeShardRouter.class);
        List<Integer> visitedShards = new ArrayList<>();
        OutboxEventEntity event = new OutboxEventEntity();
        event.setId("event-1");

        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenAnswer(ignored -> new SimpleTransactionStatus());
        when(shardRouter.shardCount()).thenReturn(2);
        when(shardRouter.executeOnShard(anyInt(), any())).thenAnswer(invocation -> {
            visitedShards.add(invocation.getArgument(0));
            Supplier<?> action = invocation.getArgument(1);
            return action.get();
        });
        when(mapper.selectExpiredClaimIdsForUpdate(any(Instant.class), anyInt()))
                .thenReturn(List.of());
        when(mapper.selectPublishableForUpdate(any(Instant.class), anyInt()))
                .thenReturn(List.of(event));
        when(mapper.claim(any(), any(), any(), any())).thenReturn(1);
        Instant now = Instant.parse("2026-07-22T00:00:00Z");
        when(mapper.currentTime()).thenReturn(now);

        OutboxClaimService service = new OutboxClaimService(
                mapper, transactionManager, shardRouter);

        service.claimBatch("publisher", Duration.ofSeconds(30), 1);
        service.claimBatch("publisher", Duration.ofSeconds(30), 1);

        assertThat(visitedShards).containsExactly(0, 1);
    }
}
