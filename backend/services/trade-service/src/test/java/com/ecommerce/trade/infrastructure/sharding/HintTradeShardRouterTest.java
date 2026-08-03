package com.ecommerce.trade.infrastructure.sharding;

import org.apache.shardingsphere.infra.hint.HintManager;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HintTradeShardRouterTest {

    @Test
    void mapsUsersDeterministicallyAndClearsHintScope() {
        HintTradeShardRouter router = new HintTradeShardRouter(2);

        assertThat(router.shardIndex(2L)).isZero();
        assertThat(router.shardIndex(3L)).isOne();
        assertThat(router.isRouted()).isFalse();

        int routed = router.executeForUser(3L, () -> {
            assertThat(router.isRouted()).isTrue();
            assertThat(HintManager.getDatabaseShardingValues()).containsExactly(1);
            return 1;
        });

        assertThat(routed).isOne();
        assertThat(router.isRouted()).isFalse();
        assertThat(HintManager.isInstantiated()).isFalse();
    }

    @Test
    void permitsNestedWorkOnTheSameShardButRejectsShardSwitching() {
        HintTradeShardRouter router = new HintTradeShardRouter(2);

        assertThat(router.executeOnShard(
                0,
                () -> router.executeForUser(2L, () -> "same-shard")))
                .isEqualTo("same-shard");

        assertThatThrownBy(() -> router.executeOnShard(
                0,
                () -> router.executeForUser(3L, () -> "different-shard")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot switch Trade shards");
        assertThat(router.isRouted()).isFalse();
    }

    @Test
    void rejectsInvalidRoutingInputs() {
        HintTradeShardRouter router = new HintTradeShardRouter(2);

        assertThatThrownBy(() -> router.shardIndex(0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> router.executeOnShard(2, () -> null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
