package com.ecommerce.platform.common.id;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * A signed-long-safe Snowflake-style generator.
 *
 * <p>The layout is 41 timestamp bits, 10 worker bits and 12 sequence bits.
 * The generator fails closed when the clock moves backwards or when its
 * external lease/ownership guard is no longer active.</p>
 */
public final class DistributedIdGenerator {

    public static final int WORKER_ID_BITS = 10;
    public static final int SEQUENCE_BITS = 12;
    public static final long MAX_WORKER_ID = (1L << WORKER_ID_BITS) - 1;
    public static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;
    public static final int WORKER_ID_SHIFT = SEQUENCE_BITS;
    public static final int TIMESTAMP_SHIFT = WORKER_ID_BITS + SEQUENCE_BITS;

    private static final long MAX_TIMESTAMP_DELTA = (1L << 41) - 1;
    private static final long MAX_SEQUENCE_WAIT_NANOS = 500_000_000L;

    private final long workerId;
    private final long epochMillis;
    private final LongSupplier clockMillis;
    private final BooleanSupplier ownershipGuard;

    private long lastTimestamp = -1L;
    private long sequence;

    public DistributedIdGenerator(long workerId, long epochMillis) {
        this(workerId, epochMillis, System::currentTimeMillis, () -> true);
    }

    public DistributedIdGenerator(
            long workerId,
            long epochMillis,
            LongSupplier clockMillis,
            BooleanSupplier ownershipGuard) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId must be between 0 and " + MAX_WORKER_ID);
        }
        if (epochMillis < 0) {
            throw new IllegalArgumentException("epochMillis must not be negative");
        }
        this.workerId = workerId;
        this.epochMillis = epochMillis;
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
        this.ownershipGuard = Objects.requireNonNull(ownershipGuard, "ownershipGuard");
    }

    public synchronized long nextId() {
        ensureOwnership();
        long timestamp = clockMillis.getAsLong();
        if (timestamp < lastTimestamp) {
            throw new ClockMovedBackwardsException(lastTimestamp, timestamp);
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                timestamp = waitUntilNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0;
        }
        long timestampDelta = timestamp - epochMillis;
        if (timestampDelta < 0 || timestampDelta > MAX_TIMESTAMP_DELTA) {
            throw new IllegalStateException("timestamp is outside the generator epoch range");
        }
        lastTimestamp = timestamp;
        return (timestampDelta << TIMESTAMP_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    public synchronized List<Long> nextIds(int count) {
        if (count < 1) {
            throw new IllegalArgumentException("count must be greater than zero");
        }
        List<Long> ids = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            ids.add(nextId());
        }
        return List.copyOf(ids);
    }

    public long workerId() {
        return workerId;
    }

    public long epochMillis() {
        return epochMillis;
    }

    public static long workerIdOf(long id) {
        return (id >>> WORKER_ID_SHIFT) & MAX_WORKER_ID;
    }

    public static long sequenceOf(long id) {
        return id & MAX_SEQUENCE;
    }

    public static long timestampMillisOf(long id, long epochMillis) {
        return (id >>> TIMESTAMP_SHIFT) + epochMillis;
    }

    private void ensureOwnership() {
        if (!ownershipGuard.getAsBoolean()) {
            throw new IllegalStateException("distributed ID worker lease is not active");
        }
    }

    private long waitUntilNextMillis(long previousTimestamp) {
        long started = System.nanoTime();
        long timestamp;
        do {
            LockSupport.parkNanos(100_000L);
            timestamp = clockMillis.getAsLong();
            if (timestamp < previousTimestamp) {
                throw new ClockMovedBackwardsException(previousTimestamp, timestamp);
            }
            if (System.nanoTime() - started > MAX_SEQUENCE_WAIT_NANOS) {
                throw new IllegalStateException("sequence exhausted before the clock advanced");
            }
        } while (timestamp <= previousTimestamp);
        sequence = 0;
        return timestamp;
    }

    public static final class ClockMovedBackwardsException extends IllegalStateException {
        public ClockMovedBackwardsException(long previousTimestamp, long currentTimestamp) {
            super("clock moved backwards: previous=" + previousTimestamp
                    + ", current=" + currentTimestamp);
        }
    }
}
