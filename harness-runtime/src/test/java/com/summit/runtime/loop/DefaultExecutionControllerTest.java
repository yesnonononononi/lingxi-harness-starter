package com.summit.runtime.loop;

import com.summit.core.agent.Agent;
import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.agent.ExecutionState;
import com.summit.core.conversation.event.ExecutionCancelledEvent;
import com.summit.core.conversation.event.ExecutionErrorEvent;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.event.TokenInfo;
import com.summit.core.conversation.message.TokenUsageEntity;
import com.summit.core.runtime.RuntimeListener;
import com.summit.core.runtime.loop.ExecutionControlSignal;
import com.summit.core.runtime.loop.ApprovalOutcome;
import com.summit.core.runtime.loop.ExecutionRepository;
import com.summit.runtime.loop.control.InMemoryActiveExecutionRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultExecutionControllerTest {

    @Test
    void failureNotificationExceptionsRemainContainedAfterCommit() {
        DeferredRepository repository = new DeferredRepository();
        RuntimeEventPublisher events = new RuntimeEventPublisher(List.of());
        DefaultRuntimeLifeStyleManager notifications = new DefaultRuntimeLifeStyleManager(events) {
            public void onError(Execution execution, Exception cause) {
                throw new IllegalStateException("observer failed");
            }
        };
        DefaultExecutionController controller = new DefaultExecutionController(
                () -> null, repository, events, notifications);
        Execution execution = Execution.builder().id("observer-failure")
                .agentRequest(AgentRequest.builder().build()).executionState(ExecutionState.CREATED).build();

        assertDoesNotThrow(() -> controller.failApproval(execution, "original failure"));
        assertDoesNotThrow(repository::commit);
        assertEquals(ExecutionState.FAILED, execution.getExecutionState());
        assertEquals("original failure", execution.getErrorMessage());
    }

    @Test
    void failureIsSavedBeforeItsDeferredNotificationAndRejectsAnotherFailure() {
        DeferredRepository repository = new DeferredRepository();
        List<ExecutionErrorEvent> notifications = new ArrayList<>();
        RuntimeEventPublisher events = new RuntimeEventPublisher(List.of(new RuntimeListener() {
            @Override
            public void onExecutionError(ExecutionErrorEvent event) {
                notifications.add(event);
            }
        }));
        DefaultExecutionController controller = new DefaultExecutionController(
                () -> agentThatExecutes(new AtomicInteger()), repository, events, new DefaultRuntimeLifeStyleManager(events));
        Execution execution = Execution.builder().id("initialization-failure")
                .agentRequest(AgentRequest.builder().build()).executionState(ExecutionState.CREATED).build();
        repository.save(execution);
        assertTrue(repository.findById(execution.getId()).isPresent());

        Runnable notification = controller.fail(execution, new IllegalStateException("model unavailable"));

        assertEquals(ExecutionState.FAILED, execution.getExecutionState());
        assertTrue(repository.findById(execution.getId()).isEmpty(), "the in-memory save removes terminal snapshots");
        assertEquals("model unavailable", execution.getErrorMessage());
        repository.commit();
        assertTrue(notifications.isEmpty(), "the caller still owns the cleanup boundary");
        execution.setTokenUsage(TokenUsageEntity.of(3, 2, 1));
        notification.run();
        assertTrue(notifications.isEmpty(), "notification must also wait for commit");
        repository.commit();
        assertEquals(1, notifications.size());
        assertEquals(3, notifications.getFirst().getTokenInfo().totalTokenCount());
        assertThrows(IllegalStateException.class,
                () -> controller.fail(execution, new IllegalStateException("late failure")));
        repository.commit();
        assertEquals(1, notifications.size());
    }

    @Test
    void resolvesTheAgentOnlyWhenResumingAndKeepsTheSuspendedStateForIt() {
        AtomicInteger resolutions = new AtomicInteger();
        AtomicInteger executions = new AtomicInteger();
        RuntimeEventPublisher events = new RuntimeEventPublisher(List.of());
        DefaultExecutionController controller = new DefaultExecutionController(() -> {
            resolutions.incrementAndGet();
            return agentThatExecutes(executions);
        }, new InMemoryActiveExecutionRegistry(), events, new DefaultRuntimeLifeStyleManager(events));
        Execution execution = Execution.builder().id("resume")
                .agentRequest(AgentRequest.builder().build())
                .executionState(ExecutionState.SUSPENDED).build();

        assertEquals(0, resolutions.get());
        assertSame(execution, controller.resume(execution));
        assertEquals(1, resolutions.get());
        assertEquals(1, executions.get());
        assertEquals(ExecutionState.SUSPENDED, execution.getExecutionState());
    }

    @Test
    void forwardsExternalSuspendAndCancelToTheActiveRun() {
        InMemoryActiveExecutionRegistry registry = new InMemoryActiveExecutionRegistry();
        ExecutionControlSignal signal = registry.register("execution-1");
        DefaultExecutionController controller = new DefaultExecutionController(
                () -> agentThatExecutes(new AtomicInteger()), registry, new RuntimeEventPublisher(List.of()),
                new DefaultRuntimeLifeStyleManager(new RuntimeEventPublisher(List.of())));

        controller.suspend("execution-1");
        assertTrue(signal.isSuspendRequired());

        controller.cancel("execution-1");
        assertTrue(signal.isCancelRequired());
        registry.unregister(signal);
    }


    /**
     * Resuming by id would decode a detached copy from the snapshot store, so the suspended
     * execution object itself would never resume and two divergent objects could overwrite the same
     * checkpoint. The ban is part of the contract, so it must fail loudly instead of forking.
     */
    @Test
    void refusesResumeByIdInsteadOfRunningADetachedCopy() {
        AtomicInteger executeCalls = new AtomicInteger();
        InMemoryActiveExecutionRegistry repository = new InMemoryActiveExecutionRegistry();
        Execution execution = Execution.builder().agentRequest(AgentRequest.builder().build())
                .id("execution-2")
                .executionState(ExecutionState.SUSPENDED)
                .build();
        repository.save(execution);
        DefaultExecutionController controller = new DefaultExecutionController(
                () -> agentThatExecutes(executeCalls), repository, new RuntimeEventPublisher(List.of()),
                new DefaultRuntimeLifeStyleManager(new RuntimeEventPublisher(List.of())));

        assertEquals(0, executeCalls.get());
    }

    @Test
    void approvalControlPersistsEachStateAndRejectsInvalidOrder() {
        InMemoryActiveExecutionRegistry repository = new InMemoryActiveExecutionRegistry();
        Execution execution = Execution.builder().agentRequest(AgentRequest.builder().build()).id("approval-1")
                .executionState(ExecutionState.SUSPENDED).build();
        DefaultExecutionController controller = new DefaultExecutionController(
                () -> agentThatExecutes(new AtomicInteger()), repository, new RuntimeEventPublisher(List.of()),
                new DefaultRuntimeLifeStyleManager(new RuntimeEventPublisher(List.of())));

        controller.beginApproval(execution);
        assertEquals(ExecutionState.RUNNING, repository.findById("approval-1").orElseThrow().getExecutionState());
        assertThrows(IllegalStateException.class, () -> controller.beginApproval(execution));

        controller.finishApproval(execution, ApprovalOutcome.CONTINUE);
        assertEquals(ExecutionState.SUSPENDED, repository.findById("approval-1").orElseThrow().getExecutionState());

        controller.beginApproval(execution);
        controller.failApproval(execution, "command result uncertain");
        assertEquals(ExecutionState.FAILED, execution.getExecutionState());
        assertEquals("command result uncertain", execution.getErrorMessage());
        assertThrows(IllegalStateException.class,
                () -> controller.finishApproval(execution, ApprovalOutcome.CONTINUE));
    }

    @Test
    void approvalTerminalEventsArePublishedOnlyAfterCommit() {
        DeferredRepository repository = new DeferredRepository();
        List<String> notifications = new ArrayList<>();
        RuntimeEventPublisher events = new RuntimeEventPublisher(List.of(new RuntimeListener() {
            @Override
            public void onExecutionCancelled(ExecutionCancelledEvent event) {
                notifications.add("cancel:" + event.executionId());
            }

            @Override
            public void onExecutionError(ExecutionErrorEvent event) {
                notifications.add("failed:" + event.executionId());
            }
        }));
        DefaultExecutionController controller = new DefaultExecutionController(
                () -> agentThatExecutes(new AtomicInteger()), repository, events, new DefaultRuntimeLifeStyleManager(events));

        Execution cancelled = Execution.builder().agentRequest(AgentRequest.builder().build()).id("cancelled")
                .executionState(ExecutionState.SUSPENDED).build();
        controller.beginApproval(cancelled);
        controller.finishApproval(cancelled, ApprovalOutcome.CANCELLED);
        assertTrue(notifications.isEmpty());
        repository.commit();
        assertEquals(List.of("cancel:cancelled"), notifications);

        Execution rolledBack = Execution.builder().agentRequest(AgentRequest.builder().build()).id("rolled-back")
                .executionState(ExecutionState.SUSPENDED).build();
        controller.beginApproval(rolledBack);
        controller.failApproval(rolledBack, "failed");
        repository.rollback();
        assertEquals(List.of("cancel:cancelled"), notifications);

        Execution failed = Execution.builder().agentRequest(AgentRequest.builder().build()).id("failed")
                .executionState(ExecutionState.SUSPENDED).build();
        controller.beginApproval(failed);
        controller.failApproval(failed, "command result uncertain");
        assertEquals(List.of("cancel:cancelled"), notifications);
        repository.commit();
        assertEquals(List.of("cancel:cancelled", "failed:failed"), notifications);
    }

    @Test
    void approvalTerminalEventsCarryAccumulatedUsage() {
        DeferredRepository repository = new DeferredRepository();
        List<TokenInfo> usage = new ArrayList<>();
        RuntimeEventPublisher events = new RuntimeEventPublisher(List.of(new RuntimeListener() {
            @Override
            public void onExecutionCancelled(ExecutionCancelledEvent event) {
                usage.add(event.getTokenInfo());
            }

            @Override
            public void onExecutionError(ExecutionErrorEvent event) {
                usage.add(event.getTokenInfo());
            }
        }));
        DefaultExecutionController controller = new DefaultExecutionController(
                () -> agentThatExecutes(new AtomicInteger()), repository, events, new DefaultRuntimeLifeStyleManager(events));

        // 还没采到用量：事件必须带 null，不能伪装成 0。
        Execution noUsage = Execution.builder().agentRequest(AgentRequest.builder().build())
                .id("no-usage").executionState(ExecutionState.SUSPENDED).build();
        controller.beginApproval(noUsage);
        controller.finishApproval(noUsage, ApprovalOutcome.CANCELLED);
        repository.commit();
        assertNull(usage.getFirst());

        Execution withUsage = Execution.builder().agentRequest(AgentRequest.builder().build())
                .id("with-usage").executionState(ExecutionState.SUSPENDED)
                .tokenUsage(TokenUsageEntity.of(150, 100, 50)).build();
        controller.beginApproval(withUsage);
        controller.failApproval(withUsage, "command result uncertain");
        repository.commit();

        TokenInfo failed = usage.get(1);
        assertNotNull(failed, "失败事件要带上失败前已累计的用量");
        assertEquals(100, failed.inputTokenCount());
        assertEquals(50, failed.outputTokenCount());
        assertEquals(150, failed.totalTokenCount());
    }

    private static final class DeferredRepository implements ExecutionRepository {
        private final InMemoryActiveExecutionRegistry delegate = new InMemoryActiveExecutionRegistry();
        private final List<Runnable> pending = new ArrayList<>();

        @Override public void save(Execution execution) { delegate.save(execution); }
        @Override public Optional<Execution> findById(String id) { return delegate.findById(id); }
        @Override public ExecutionControlSignal register(String id) { return delegate.register(id); }
        @Override public void unregister(ExecutionControlSignal signal) { delegate.unregister(signal); }
        @Override public void requireSuspend(String id) { delegate.requireSuspend(id); }
        @Override public void requireCancel(String id) { delegate.requireCancel(id); }
        @Override public void afterCommit(Runnable notification) { pending.add(notification); }

        void commit() {
            List<Runnable> committed = List.copyOf(pending);
            pending.clear();
            committed.forEach(Runnable::run);
        }

        void rollback() { pending.clear(); }
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

            @Override
            public Execution createExecution(AgentRequest agentRequest) {
                return null;
            }
        };
    }
}
