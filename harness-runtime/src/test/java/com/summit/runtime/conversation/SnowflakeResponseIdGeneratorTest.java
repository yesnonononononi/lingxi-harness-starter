package com.summit.runtime.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.agent.ExecutionState;
import com.summit.core.json.ExecutionJson;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class SnowflakeResponseIdGeneratorTest {
    private static final long NOW = 1791504000000L;

    @Test
    void fixedClockAndSequenceOverflowRemainStrictlyIncreasing() {
        SnowflakeResponseIdGenerator generator = new SnowflakeResponseIdGenerator(7, () -> NOW);
        long last = 0;
        for (int index = 0; index < 10000; index++) {
            String next = generator.nextId(null);
            long id = Long.parseLong(next);
            assertTrue(id > last);
            assertEquals(7, (id >>> 12) & 1023);
            assertEquals(Long.toString(id), next);
            last = id;
        }
    }

    @Test
    void concurrentExecutionsAndSeparateWorkersDoNotCollide() throws Exception {
        SnowflakeResponseIdGenerator first = new SnowflakeResponseIdGenerator(1, () -> NOW);
        SnowflakeResponseIdGenerator second = new SnowflakeResponseIdGenerator(2, () -> NOW);
        ConcurrentHashMap<String, Boolean> seen = new ConcurrentHashMap<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> tasks = new ArrayList<>();
            for (int index = 0; index < 1000; index++) {
                tasks.add(executor.submit(() -> {
                    assertNull(seen.putIfAbsent(first.nextId(null), true));
                    assertNull(seen.putIfAbsent(second.nextId(null), true));
                }));
            }
            for (Future<?> task : tasks) task.get();
        }
        assertEquals(2000, seen.size());
    }

    @Test
    void clockRollbackDoesNotRegressTheGenerator() {
        AtomicLong clock = new AtomicLong(NOW);
        SnowflakeResponseIdGenerator generator = new SnowflakeResponseIdGenerator(3, clock::get);
        long first = Long.parseLong(generator.nextId(null));
        clock.addAndGet(-10000);
        assertTrue(Long.parseLong(generator.nextId(null)) > first);
    }

    @Test
    void restoredExecutionRetainsItsLowerBoundOnAFreshWorkerAndOlderClock() throws Exception {
        ObjectMapper mapper = ExecutionJson.newObjectMapper();
        Execution execution = Execution.builder().id("execution-1")
                .executionState(ExecutionState.SUSPENDED).agentRequest(AgentRequest.builder().build()).build();
        String original = execution.nextResponseId(new SnowflakeResponseIdGenerator(900, () -> NOW));
        byte[] snapshot = mapper.writeValueAsBytes(execution);
        assertTrue(mapper.readTree(snapshot).get("lastResponseId").isTextual());
        Execution restored = mapper.readValue(snapshot, Execution.class);
        assertEquals(original, restored.getLastResponseId());
        assertEquals(ExecutionState.SUSPENDED, restored.getExecutionState());

        String resumed = restored.nextResponseId(new SnowflakeResponseIdGenerator(1, () -> NOW - 10000));
        assertTrue(Long.parseLong(resumed) > Long.parseLong(original));
        assertEquals(resumed, restored.getLastResponseId());
    }

    @Test
    void executionRejectsRegressingOrMalformedCustomGeneratorsWithoutChangingItsLowerBound() {
        Execution execution = Execution.builder().lastResponseId("100")
                .agentRequest(AgentRequest.builder().build()).build();
        for (String invalid : List.of("99", "100", "0", "-1", "0101", "+101", "uuid", "9223372036854775808")) {
            assertThrows(RuntimeException.class, () -> execution.nextResponseId(previous -> invalid));
            assertEquals("100", execution.getLastResponseId());
        }
        assertThrows(NullPointerException.class, () -> execution.nextResponseId(previous -> null));
        assertEquals("101", execution.nextResponseId(previous -> {
            assertEquals("100", previous);
            return "101";
        }));
    }

    @Test
    void rejectsInvalidWorkersAndExhaustedTimestamp() {
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeResponseIdGenerator(-1));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeResponseIdGenerator(1024));
        SnowflakeResponseIdGenerator generator = new SnowflakeResponseIdGenerator(1, () -> NOW);
        assertThrows(IllegalStateException.class, () -> generator.nextId(Long.toString(Long.MAX_VALUE)));
    }
}
