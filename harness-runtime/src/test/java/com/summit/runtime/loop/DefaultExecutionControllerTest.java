package com.summit.runtime.loop;

import com.summit.core.agent.Agent;
import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.agent.ExecutionState;
import com.summit.core.runtime.loop.ExecutionControlSignal;
import com.summit.runtime.loop.control.InMemoryActiveExecutionRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultExecutionControllerTest {

    @Test
    void forwardsExternalSuspendAndCancelToTheActiveRun() {
        InMemoryActiveExecutionRegistry registry = new InMemoryActiveExecutionRegistry();
        ExecutionControlSignal signal = registry.register("execution-1");
        DefaultExecutionController controller = new DefaultExecutionController(
                agentThatExecutes(new AtomicInteger()), registry);

        controller.suspend("execution-1");
        assertTrue(signal.isSuspendRequired());

        controller.cancel("execution-1");
        assertTrue(signal.isCancelRequired());
        registry.unregister(signal);
    }


    @Test
    void resumesSavedExecutionById() {
        AtomicInteger executeCalls = new AtomicInteger();
        InMemoryActiveExecutionRegistry repository = new InMemoryActiveExecutionRegistry();
        Execution execution = Execution.builder()
                .id("execution-2")
                .executionState(ExecutionState.SUSPENDED)
                .build();
        repository.save(execution);
        DefaultExecutionController controller = new DefaultExecutionController(
                agentThatExecutes(executeCalls), repository);

        assertEquals(execution, controller.resume("execution-2"));
        assertEquals(1, executeCalls.get());
    }



    private Agent agentThatExecutes(AtomicInteger executeCalls) {
        return new Agent() {
            @Override
            public String id() {
                return "test-agent";
            }

            @Override
            public Execution execute(AgentRequest agentRequest) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Execution execute(Execution execution) {
                executeCalls.incrementAndGet();
                return execution;
            }
        };
    }
}
