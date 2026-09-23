package com.summit.runtime.loop.control;

import com.summit.core.runtime.loop.ExecutionControlSignal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryActiveExecutionRegistryTest {

    @Test
    void rejectsConcurrentRegistrationAndAllowsANewRunAfterUnregister() {
        InMemoryActiveExecutionRegistry registry = new InMemoryActiveExecutionRegistry();
        ExecutionControlSignal first = registry.register("execution-1");

        assertThrows(IllegalStateException.class, () -> registry.register("execution-1"));

        registry.unregister(first);
        ExecutionControlSignal second = registry.register("execution-1");
        assertThrows(IllegalArgumentException.class, () -> registry.unregister(first));
        registry.unregister(second);
    }

    @Test
    void publishesSuspendAndCancelIntentionsToTheActiveRun() {
        InMemoryActiveExecutionRegistry registry = new InMemoryActiveExecutionRegistry();
        ExecutionControlSignal signal = registry.register("execution-1");

        registry.requireSuspend("execution-1");
        assertTrue(signal.isSuspendRequired());

        registry.requireCancel("execution-1");
        assertTrue(signal.isCancelRequired());
        registry.unregister(signal);
    }

    @Test
    void rejectsControlRequestsForExecutionsThatAreNotRunning() {
        InMemoryActiveExecutionRegistry registry = new InMemoryActiveExecutionRegistry();

        assertThrows(IllegalStateException.class, () -> registry.requireSuspend("missing"));
        assertThrows(IllegalStateException.class, () -> registry.requireCancel("missing"));
    }
}
