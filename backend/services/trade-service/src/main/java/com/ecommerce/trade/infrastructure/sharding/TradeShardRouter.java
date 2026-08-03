package com.ecommerce.trade.infrastructure.sharding;

import java.util.function.Supplier;

public interface TradeShardRouter {

    boolean isRouted();

    int shardCount();

    int shardIndex(long userId);

    <T> T executeForUser(long userId, Supplier<T> action);

    default void runForUser(long userId, Runnable action) {
        executeForUser(userId, () -> {
            action.run();
            return null;
        });
    }

    <T> T executeOnShard(int shardIndex, Supplier<T> action);

    default void runOnShard(int shardIndex, Runnable action) {
        executeOnShard(shardIndex, () -> {
            action.run();
            return null;
        });
    }
}
