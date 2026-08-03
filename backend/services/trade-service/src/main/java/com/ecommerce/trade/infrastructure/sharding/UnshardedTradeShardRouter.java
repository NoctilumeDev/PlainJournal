package com.ecommerce.trade.infrastructure.sharding;

import java.util.Objects;
import java.util.function.Supplier;

public final class UnshardedTradeShardRouter implements TradeShardRouter {

    @Override
    public boolean isRouted() {
        return true;
    }

    @Override
    public int shardCount() {
        return 1;
    }

    @Override
    public int shardIndex(long userId) {
        return 0;
    }

    @Override
    public <T> T executeForUser(long userId, Supplier<T> action) {
        return Objects.requireNonNull(action, "action").get();
    }

    @Override
    public <T> T executeOnShard(int shardIndex, Supplier<T> action) {
        if (shardIndex != 0) {
            throw new IllegalArgumentException("Unsharded mode only exposes shard 0");
        }
        return Objects.requireNonNull(action, "action").get();
    }
}
