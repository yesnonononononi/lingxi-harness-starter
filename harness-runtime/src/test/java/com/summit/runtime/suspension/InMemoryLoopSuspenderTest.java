package com.summit.runtime.suspension;

import com.summit.core.runtime.SuspensionDecision;
import com.summit.core.runtime.SuspensionRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryLoopSuspenderTest {

    @Test
    void registersBeforeNotificationAndResumesWithApplicationPayload() throws Exception {
        InMemoryLoopSuspender suspender = new InMemoryLoopSuspender();
        SuspensionRequest request = new SuspensionRequest("decision-1", "execution-1", "session-1",
                "test", Map.of("question", "continue?"), Duration.ofSeconds(2));

        CompletableFuture<SuspensionDecision> waiting = CompletableFuture.supplyAsync(() ->
                suspender.suspend(request, suspension -> {
                    assertTrue(suspender.find("decision-1").isPresent());
                    assertTrue(suspender.resolve("decision-1",
                            SuspensionDecision.resume(Map.of("answer", "yes"))));
                }));

        SuspensionDecision decision = waiting.get(2, TimeUnit.SECONDS);
        assertEquals(SuspensionDecision.Status.RESUMED, decision.status());
        assertEquals("yes", decision.payload().get("answer"));
        assertTrue(suspender.find("decision-1").isEmpty());
    }

    @Test
    void timesOutWithoutLeakingPendingState() {
        InMemoryLoopSuspender suspender = new InMemoryLoopSuspender();
        SuspensionDecision decision = suspender.suspend(new SuspensionRequest("timeout-1", "execution-1",
                "session-1", "test", Map.of(), Duration.ofMillis(10)));

        assertEquals(SuspensionDecision.Status.TIMED_OUT, decision.status());
        assertTrue(suspender.find("timeout-1").isEmpty());
    }

    @Test
    void notificationFailureDoesNotLeakPendingState() {
        InMemoryLoopSuspender suspender = new InMemoryLoopSuspender();
        SuspensionRequest request = new SuspensionRequest("broken-listener", "execution-1", "session-1",
                "test", Map.of(), Duration.ofSeconds(1));

        assertThrows(IllegalStateException.class,
                () -> suspender.suspend(request, ignored -> { throw new IllegalStateException("boom"); }));
        assertTrue(suspender.find("broken-listener").isEmpty());
    }
}
