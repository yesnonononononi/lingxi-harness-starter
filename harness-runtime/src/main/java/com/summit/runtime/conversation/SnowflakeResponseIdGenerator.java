package com.summit.runtime.conversation;

import com.summit.core.conversation.api.ResponseIdGenerator;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Generates 64-bit IDs with 41 timestamp bits, 10 worker bits and 12 sequence bits.
 * Workers must have distinct IDs; reuse requires the previous worker's logical time to have passed.
 * Restored execution IDs provide a lower bound after clock rollback or worker migration.
 */
public final class SnowflakeResponseIdGenerator implements ResponseIdGenerator {
    /** Shared fallback for runtimes assembled without Spring; one worker per JVM by default. */
    public static final SnowflakeResponseIdGenerator DEFAULT = new SnowflakeResponseIdGenerator(0);
    private static final long EPOCH_MILLIS = 1577836800000L;
    private static final int TIMESTAMP_SHIFT = 22;
    private static final int WORKER_SHIFT = 12;
    private static final long MAX_WORKER_ID = 1023L;
    private static final long MAX_TIMESTAMP = (1L << 41) - 1;
    private static final long SEQUENCE_MASK = 4095L;

    private final long workerId;
    private final LongSupplier clock;
    private long lastId;

    public SnowflakeResponseIdGenerator(long workerId) {
        this(workerId, System::currentTimeMillis);
    }

    SnowflakeResponseIdGenerator(long workerId, LongSupplier clock) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("response ID worker must be between 0 and 1023");
        }
        this.workerId = workerId;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized String nextId(String previousId) {
        long previous = previousId == null ? 0 : Long.parseLong(previousId);
        if (previousId != null && (previous <= 0 || !Long.toString(previous).equals(previousId))) {
            throw new IllegalArgumentException("previous response ID must be a positive decimal integer");
        }
        long floor = Math.max(lastId, previous);
        long elapsed = clock.getAsLong() - EPOCH_MILLIS;
        if (elapsed < 0) {
            throw new IllegalStateException("response ID clock precedes the 2020 epoch");
        }
        long timestamp = Math.max(elapsed, floor >>> TIMESTAMP_SHIFT);
        checkTimestamp(timestamp);
        long next = (timestamp << TIMESTAMP_SHIFT) | (workerId << WORKER_SHIFT);
        if (next <= floor) {
            long sequence = (floor & SEQUENCE_MASK) + 1;
            long previousWorker = (floor >>> WORKER_SHIFT) & MAX_WORKER_ID;
            if (previousWorker == workerId && sequence <= SEQUENCE_MASK) {
                next |= sequence;
            } else {
                // Advance logical time rather than block on a stalled clock or sequence overflow.
                checkTimestamp(++timestamp);
                next = (timestamp << TIMESTAMP_SHIFT) | (workerId << WORKER_SHIFT);
            }
        }
        lastId = next;
        return Long.toString(next);
    }

    private static void checkTimestamp(long timestamp) {
        if (timestamp > MAX_TIMESTAMP) {
            throw new IllegalStateException("response ID timestamp exhausted");
        }
    }
}
