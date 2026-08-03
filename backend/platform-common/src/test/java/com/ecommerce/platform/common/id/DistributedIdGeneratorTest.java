package com.ecommerce.platform.common.id;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class DistributedIdGeneratorTest {

    @Test
    void generatesUniqueMonotonicIdsWithinOneMillisecond() {
        AtomicLong clock = new AtomicLong(1_800_000_000_000L);
        DistributedIdGenerator generator = new DistributedIdGenerator(
                7, 1_700_000_000_000L, clock::get, () -> true);

        List<Long> ids = generator.nextIds(1_000);

        assertThat(ids).doesNotHaveDuplicates();
        for (int index = 1; index < ids.size(); index++) {
            assertThat(ids.get(index)).isGreaterThan(ids.get(index - 1));
        }
        assertThat(ids).allSatisfy(id -> {
            assertThat(DistributedIdGenerator.workerIdOf(id)).isEqualTo(7);
            assertThat(DistributedIdGenerator.timestampMillisOf(id, 1_700_000_000_000L))
                    .isEqualTo(clock.get());
        });
    }

    @Test
    void differentWorkersProduceDisjointIds() {
        AtomicLong clock = new AtomicLong(1_800_000_000_000L);
        DistributedIdGenerator first = new DistributedIdGenerator(
                1, 1_700_000_000_000L, clock::get, () -> true);
        DistributedIdGenerator second = new DistributedIdGenerator(
                2, 1_700_000_000_000L, clock::get, () -> true);

        Set<Long> ids = new HashSet<>(first.nextIds(2_000));
        assertThat(ids).doesNotContainAnyElementsOf(second.nextIds(2_000));
        assertThat(ids).hasSize(2_000);
    }

    @Test
    void failsClosedWhenClockMovesBackwards() {
        AtomicLong clock = new AtomicLong(1_800_000_000_010L);
        DistributedIdGenerator generator = new DistributedIdGenerator(
                0, 1_700_000_000_000L, clock::get, () -> true);
        generator.nextId();
        clock.set(1_800_000_000_009L);

        assertThatIllegalStateException()
                .isThrownBy(generator::nextId)
                .withMessageContaining("clock moved backwards");
    }

    @Test
    void refusesIdsAfterLeaseGuardIsLost() {
        AtomicBoolean owned = new AtomicBoolean(true);
        DistributedIdGenerator generator = new DistributedIdGenerator(
                0, 1_700_000_000_000L, System::currentTimeMillis, owned::get);
        generator.nextId();
        owned.set(false);

        assertThatIllegalStateException()
                .isThrownBy(generator::nextId)
                .withMessageContaining("worker lease is not active");
    }

    @Test
    void validatesWorkerRangeAndBatchSize() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DistributedIdGenerator(1024, 1_700_000_000_000L));
        DistributedIdGenerator generator = new DistributedIdGenerator(0, 1_700_000_000_000L);
        assertThatIllegalArgumentException().isThrownBy(() -> generator.nextIds(0));
    }
}
