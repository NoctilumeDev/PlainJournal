package com.ecommerce.trade.infrastructure.sharding;

import org.apache.shardingsphere.infra.hint.HintManager;

import java.util.Objects;
import java.util.function.Supplier;

public final class HintTradeShardRouter implements TradeShardRouter {

    private final int shardCount;
    private final ThreadLocal<Integer> currentShard = new ThreadLocal<>();

    public HintTradeShardRouter(int shardCount) {
        if (shardCount < 2) {
            throw new IllegalArgumentException("Sharded mode requires at least two shards");
        }
        this.shardCount = shardCount;
    }

    @Override
    public boolean isRouted() {
        return currentShard.get() != null;
    }

    @Override
    public int shardCount() {
        return shardCount;
    }

    @Override
    public int shardIndex(long userId) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        return Math.floorMod(userId, shardCount);
    }

    @Override
    public <T> T executeForUser(long userId, Supplier<T> action) {
        return executeOnShard(shardIndex(userId), action);
    }

    @Override
    public <T> T executeOnShard(int shardIndex, Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        if (shardIndex < 0 || shardIndex >= shardCount) {
            throw new IllegalArgumentException("shardIndex is outside the configured range");
        }
        Integer existing = currentShard.get();
        if (existing != null) {
            if (existing != shardIndex) {
                throw new IllegalStateException(
                        "A local transaction cannot switch Trade shards: current="
                                + existing + ", requested=" + shardIndex);
            }
            return action.get();
        }
        try (HintManager hintManager = HintManager.getInstance()) {
            currentShard.set(shardIndex);
            hintManager.setDatabaseShardingValue(shardIndex);
            return action.get();
        } finally {
            currentShard.remove();
        }
    }
}
