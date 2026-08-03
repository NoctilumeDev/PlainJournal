package com.ecommerce.trade.infrastructure.messaging;

import com.ecommerce.platform.common.observability.ConsumerFailureEntry;
import com.ecommerce.platform.common.observability.ConsumerFailureRetryEntry;
import com.ecommerce.platform.common.observability.ConsumerFailureRetryStore;
import com.ecommerce.trade.infrastructure.persistence.mapper.ConsumerFailureMapper;
import com.ecommerce.trade.infrastructure.sharding.TradeShardRouter;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class TradeConsumerFailureRetryStore implements ConsumerFailureRetryStore {

    private final ConsumerFailureMapper mapper;
    private final TradeShardRouter shardRouter;

    public TradeConsumerFailureRetryStore(
            ConsumerFailureMapper mapper,
            TradeShardRouter shardRouter) {
        this.mapper = mapper;
        this.shardRouter = shardRouter;
    }

    @Override
    public Instant currentTime() {
        return shardRouter.executeOnShard(0, mapper::currentTime);
    }

    @Override
    public List<ConsumerFailureRetryEntry> selectRetryable(Instant now, int limit) {
        return shardRouter.executeOnShard(0, () -> mapper.selectRetryable(now, limit));
    }

    @Override
    public int claimRetry(
            String messageId,
            String consumerGroup,
            String owner,
            int expectedAttempts,
            Instant now,
            Instant claimUntil) {
        return shardRouter.executeOnShard(0, () -> mapper.claimRetry(
                messageId,
                consumerGroup,
                owner,
                expectedAttempts,
                now,
                claimUntil));
    }

    @Override
    public int markRetryRecovered(
            String messageId,
            String consumerGroup,
            String owner,
            Instant now) {
        return shardRouter.executeOnShard(0, () -> mapper.markRetryRecovered(
                messageId,
                consumerGroup,
                owner,
                now));
    }

    @Override
    public int markRetryFailed(
            String messageId,
            String consumerGroup,
            String owner,
            int attempts,
            String status,
            String error,
            Instant nextAttemptAt,
            Instant now) {
        return shardRouter.executeOnShard(0, () -> mapper.markRetryFailed(
                messageId,
                consumerGroup,
                owner,
                attempts,
                status,
                error,
                nextAttemptAt,
                now));
    }

    @Override
    public long countByStatus(String status) {
        return shardRouter.executeOnShard(0, () -> mapper.countByStatus(status));
    }

    @Override
    public Instant selectOldestActiveFailedAt() {
        return shardRouter.executeOnShard(0, mapper::selectOldestActiveFailedAt);
    }

    @Override
    public List<ConsumerFailureEntry> selectRecentActive(int limit) {
        return shardRouter.executeOnShard(0, () -> mapper.selectRecentActive(limit));
    }
}
