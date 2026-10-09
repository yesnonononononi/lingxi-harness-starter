package com.summit.runtime.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.event.ToolCallEndEvent;
import com.summit.core.conversation.event.ToolCallStartEvent;
import com.summit.core.json.ExecutionJson;
import com.summit.core.runtime.RuntimeListener;
import com.summit.core.tool.ConcurrentPolicy;
import com.summit.core.tool.ToolCallStatus;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecuteCommand;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecution;
import com.summit.core.tool.ToolExecutionContext;
import com.summit.core.tool.ToolExecutionPolicy;
import com.summit.core.tool.ToolExecutor;
import com.summit.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRequestIndexPropagationTest {
    private static final String RESPONSE_ID = "1234567890123456789";

    @Test
    void completionOrderDoesNotChangeIndicesOrReturnedRequestOrder() throws Exception {
        CountDownLatch secondCompleted = new CountDownLatch(1);
        RecordingListener listener = new RecordingListener() {
            @Override
            public void onToolCallOutput(ToolCallEndEvent event) {
                super.onToolCallOutput(event);
                if (event.getRequestId().equals("call-1")) secondCompleted.countDown();
            }
        };
        ToolDefinition<?> tool = tool(10L, execution -> {
            if (execution.getId().equals("call-0") && !await(secondCompleted, 5)) {
                throw new IllegalStateException("second call did not complete independently");
            }
            return ToolExecuteResult.success(execution.getId());
        });
        List<ToolCallRequest> requests = IntStream.range(0, 4)
                .mapToObj(index -> new ToolCallRequest("call-" + index, "test", index, "{}"))
                .toList();

        try (DefaultToolExecutionManager manager = manager(tool, listener, List.of())) {
            List<ToolExecuteResult> results = manager.execute(command(requests, List.of("test")));
            assertEquals(List.of("call-0", "call-1", "call-2", "call-3"), results.stream()
                    .map(ToolExecuteResult::getId).toList());
            assertEquals(List.of(0, 1, 2, 3), results.stream()
                    .map(ToolExecuteResult::getRequestIndex).toList());
            assertEquals(List.of("call-1", "call-0", "call-2", "call-3"), listener.ends.stream()
                    .map(ToolCallEndEvent::getRequestId).toList());
            assertEquals(4, listener.starts.size());
            for (ToolCallStartEvent start : listener.starts) {
                assertEquals(requests.get(start.getRequestIndex()).id(), start.getRequestId());
                assertEquals(RESPONSE_ID, start.getResponseId());
                assertEquals("execution-1", start.executionId());
                JsonNode json = ExecutionJson.newObjectMapper().valueToTree(start);
                assertTrue(json.get("requestIndex").isIntegralNumber());
                assertEquals(start.getRequestIndex(), json.get("requestIndex").intValue());
            }
            for (ToolCallEndEvent end : listener.ends) {
                assertEquals(requests.get(end.getRequestIndex()).id(), end.getRequestId());
                assertEquals(RESPONSE_ID, end.getResponseId());
                assertEquals("execution-1", end.executionId());
                JsonNode json = ExecutionJson.newObjectMapper().valueToTree(end);
                assertTrue(json.get("requestIndex").isIntegralNumber());
                assertEquals(end.getRequestIndex(), json.get("requestIndex").intValue());
            }
        }
    }

    @Test
    void successFailurePromiseNullAndThrownResultsRetainRequestIndex() {
        List<ToolExecutor> executors = List.of(
                execution -> ToolExecuteResult.success("ok"),
                execution -> ToolExecuteResult.err("failed"),
                execution -> ToolExecuteResult.promise("waiting"),
                execution -> null,
                execution -> { throw new IllegalStateException("boom"); });
        List<ToolCallStatus> statuses = List.of(ToolCallStatus.COMPLETED, ToolCallStatus.FAILED,
                ToolCallStatus.PROMISED, ToolCallStatus.FAILED, ToolCallStatus.FAILED);
        for (int i = 0; i < executors.size(); i++) {
            RecordingListener listener = new RecordingListener();
            ToolCallRequest request = new ToolCallRequest("call-" + i, "test", i + 3, "{}");
            try (DefaultToolExecutionManager manager = manager(tool(0L, executors.get(i)), listener, List.of())) {
                ToolExecuteResult result = manager.execute(command(List.of(request), List.of("test"))).getFirst();
                assertIdentity(request, result, listener, statuses.get(i));
                assertEquals(1, listener.starts.size());
                assertEquals(request.requestIndex(), listener.starts.getFirst().getRequestIndex());
            }
        }
    }

    @Test
    void unknownUnauthorizedAndPolicyRejectedCallsRetainPositionWithoutStartEvent() {
        for (int i = 0; i < 3; i++) {
            RecordingListener listener = new RecordingListener();
            ToolCallRequest request = new ToolCallRequest("rejected-" + i, i == 0 ? "missing" : "test", i + 4, "{}");
            ToolDefinition<?> tool = tool(0L, execution -> { throw new AssertionError("rejected tool executed"); });
            List<ToolExecutionPolicy> policies = i == 2
                    ? List.of(execution -> ToolExecuteResult.err("policy denied")) : List.of();
            try (DefaultToolExecutionManager manager = manager(tool, listener, policies)) {
                ToolExecuteResult result = manager.execute(command(List.of(request),
                        i == 1 ? List.of() : List.of("test"))).getFirst();
                assertIdentity(request, result, listener, ToolCallStatus.REJECTED);
                assertTrue(listener.starts.isEmpty());
            }
        }
    }

    @Test
    void timedOutCallRetainsPositionOnItsTerminalEvent() {
        RecordingListener listener = new RecordingListener();
        CountDownLatch neverReleased = new CountDownLatch(1);
        ToolDefinition<?> tool = tool(1L, execution -> {
            await(neverReleased, 30);
            return ToolExecuteResult.success("late");
        });
        ToolCallRequest request = new ToolCallRequest("timeout", "test", 7, "{}");
        try (DefaultToolExecutionManager manager = manager(tool, listener, List.of())) {
            ToolExecuteResult result = manager.execute(command(List.of(request), List.of("test"))).getFirst();
            assertIdentity(request, result, listener, ToolCallStatus.TIMED_OUT);
            assertEquals(7, listener.starts.getFirst().getRequestIndex());
        } finally {
            neverReleased.countDown();
        }
    }

    private static void assertIdentity(ToolCallRequest request, ToolExecuteResult result,
                                       RecordingListener listener, ToolCallStatus status) {
        assertEquals(request.id(), result.getId());
        assertEquals(request.requestIndex(), result.getRequestIndex());
        assertEquals(1, listener.ends.size());
        ToolCallEndEvent event = listener.ends.getFirst();
        assertEquals(request.id(), event.getRequestId());
        assertEquals(request.requestIndex(), event.getRequestIndex());
        assertEquals(RESPONSE_ID, event.getResponseId());
        assertEquals("execution-1", event.executionId());
        assertEquals(status, event.resultStatus());
    }

    private static ToolDefinition<?> tool(long timeout, ToolExecutor executor) {
        ToolExecutor hostExecutor = new ToolExecutor() {
            @Override
            public ToolExecuteResult execute(ToolExecution execution) { return executor.execute(execution); }

            @Override
            public boolean requiresWorkspace() { return false; }
        };
        return ToolDefinition.builder().id("test").name("test").maxOutput(1000).timeout(timeout)
                .concurrentPolicy(ConcurrentPolicy.READ_ONLY).executor(hostExecutor).build();
    }

    private static boolean await(CountDownLatch latch, long seconds) {
        try {
            return latch.await(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test tool interrupted", interrupted);
        }
    }

    private static DefaultToolExecutionManager manager(ToolDefinition<?> tool, RecordingListener listener,
                                                       List<ToolExecutionPolicy> policies) {
        ToolExecutionContext context = ToolExecutionContext.builder()
                .toolRegistry(new ToolRegistry(List.of(tool))).concurrentToolLimit(2)
                .runtimeEventPublisher(new RuntimeEventPublisher(List.of(listener))).build();
        return new DefaultToolExecutionManager(context,
                invocation -> invocation.getMethod().invoke(invocation.getTarget(), invocation.getContext()), policies);
    }

    private static ToolExecuteCommand command(List<ToolCallRequest> requests, List<String> allowedTools) {
        return new ToolExecuteCommand(requests, "execution-1", null, Map.of(), allowedTools, RESPONSE_ID);
    }

    private static class RecordingListener implements RuntimeListener {
        private final List<ToolCallStartEvent> starts = new CopyOnWriteArrayList<>();
        private final List<ToolCallEndEvent> ends = new CopyOnWriteArrayList<>();

        @Override
        public void onToolCall(ToolCallStartEvent event) { starts.add(event); }

        @Override
        public void onToolCallOutput(ToolCallEndEvent event) { ends.add(event); }
    }
}
