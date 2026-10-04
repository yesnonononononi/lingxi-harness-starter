package com.summit.runtime.loop.lifeStyle;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.agent.ExecutionState;
import com.summit.core.conversation.event.AgentEvent;
import com.summit.core.conversation.event.ExecutionResumedEvent;
import com.summit.core.conversation.event.ExecutionSuspendedEvent;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.event.RuntimeEventType;
import com.summit.core.conversation.message.TokenUsageEntity;
import com.summit.core.runtime.RuntimeListener;
import com.summit.runtime.loop.DefaultRuntimeLifeStyleManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Suspension and resume each publish their own event, symmetric with the other lifecycle
 * transitions. A suspension is not terminal, so it must be observable on its own instead of
 * being inferred from the resume that may never come.
 */
class DefaultRuntimeLifeStyleManagerSuspensionEventTest {

    private final List<AgentEvent> published = new ArrayList<>();
    private final DefaultRuntimeLifeStyleManager manager = new DefaultRuntimeLifeStyleManager(
            new RuntimeEventPublisher(List.of(recordingListener())));

    @Test
    void suspendPublishesASuspendedEventAfterFrameworkTransition() {
        Execution execution = execution();

        execution.suspendChecked();
        manager.onSuspend(execution);

        assertEquals(ExecutionState.SUSPENDED, execution.getExecutionState());
        ExecutionSuspendedEvent event = assertInstanceOf(ExecutionSuspendedEvent.class,
                published.getFirst());
        assertEquals("e-1", event.executionId());
        assertEquals(RuntimeEventType.EXECUTION_SUSPENDED.type(), event.type());
    }

    @Test
    void resumePublishesAResumedEventAndSuspensionIsNotMistakenForIt() {
        Execution execution = execution();

        execution.suspendChecked();
        manager.onSuspend(execution);
        execution.resumeChecked();
        manager.onResume(execution);

        assertEquals(ExecutionState.RUNNING, execution.getExecutionState());
        assertEquals(2, published.size());
        assertInstanceOf(ExecutionSuspendedEvent.class, published.get(0));
        ExecutionResumedEvent resumed = assertInstanceOf(ExecutionResumedEvent.class, published.get(1));
        assertEquals("e-1", resumed.executionId());
    }

    @Test
    void suspendedTypeIsItsOwnDiscriminatorNotTheResumeOne() {
        manager.onSuspend(execution());

        assertTrue(published.stream().anyMatch(event -> "EXECUTION_SUSPENDED".equals(event.type())));
        assertTrue(published.stream().noneMatch(event -> "EXECUTION_RESUME".equals(event.type())));
    }

    private RuntimeListener recordingListener() {
        return new RuntimeListener() {
            @Override
            public void onExecutionSuspended(ExecutionSuspendedEvent event) {
                published.add(event);
            }

            @Override
            public void onExecutionResumed(ExecutionResumedEvent event) {
                published.add(event);
            }
        };
    }

    private static Execution execution() {
        return Execution.builder().agentRequest(AgentRequest.builder().build())
                .id("e-1").agentId("a")
                .executionState(ExecutionState.RUNNING)
                .tokenUsage(TokenUsageEntity.empty())
                .build();
    }
}
