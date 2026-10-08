package com.summit.runtime.tool;

import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.event.ToolCallEndEvent;
import com.summit.core.runtime.RuntimeEnvironment;
import com.summit.core.runtime.RuntimeListener;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.tool.ConcurrentPolicy;
import com.summit.core.tool.ToolCallStatus;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecuteCommand;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecutionContext;
import com.summit.core.tool.ToolRegistry;
import com.summit.core.tool.ToolResultType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tool's {@code toolMetaData} must reach listeners on the {@code ToolCallEndEvent}, merged with the
 * command's {@code eventMetaData} (which wins conflicts; tool metadata fills absent keys), for
 * success, promise and error results alike, without leaking between calls in a concurrent batch.
 */
class DefaultToolExecutionManagerMetaDataTest {

    @Test
    void toolMetaDataFillsAbsentEventMetaDataKeys() {
        ToolCallEndEvent event = executeReturning(
                ToolExecuteResult.success("ok", ToolResultType.NORMAL, Map.of("shared", "tool", "toolKey", 42)),
                Map.of("root", "exec-1", "shared", "event")).event();

        assertEquals(Map.of("root", "exec-1", "shared", "event", "toolKey", 42), event.eventMetaData());
        assertEquals(ToolCallStatus.COMPLETED, event.resultStatus());
    }

    @Test
    void promiseCarriesMetaDataForSuspendedRuns() {
        ToolCallEndEvent event = executeReturning(
                ToolExecuteResult.promise("waiting", Map.of("delegation", "sub-1")),
                Map.of("root", "exec-1")).event();

        assertEquals(ToolCallStatus.PROMISED, event.resultStatus());
        assertEquals(Map.of("root", "exec-1", "delegation", "sub-1"), event.eventMetaData());
    }

    @Test
    void errorResultCarriesMetaData() {
        ToolCallEndEvent event = executeReturning(
                ToolExecuteResult.err("boom", ToolResultType.NORMAL, Map.of("attempt", 2)),
                Map.of("root", "exec-1")).event();

        assertEquals(ToolCallStatus.FAILED, event.resultStatus());
        assertEquals(Map.of("root", "exec-1", "attempt", 2), event.eventMetaData());
    }

    @Test
    void missingToolMetaDataFallsBackToEventMetaData() {
        ToolCallEndEvent event = executeReturning(ToolExecuteResult.success("ok"), Map.of("root", "exec-1")).event();

        assertEquals(Map.of("root", "exec-1"), event.eventMetaData());
    }

    @Test
    void publishedMetaDataIsIsolatedFromResultMap() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("key", "before");
        ToolExecuteResult result = ToolExecuteResult.success("ok", ToolResultType.NORMAL, mutable);

        ToolCallEndEvent event = executeReturning(result, Map.of()).event();

        mutable.put("key", "mutated-after-execution");
        assertEquals("before", event.eventMetaData().get("key"));
        assertThrows(UnsupportedOperationException.class, () -> event.eventMetaData().put("x", "y"));
    }

    @Test
    void concurrentBatchKeepsMetaDataPerCall() {
        Queue<ToolCallEndEvent> events = new ConcurrentLinkedQueue<>();
        RuntimeListener listener = new RuntimeListener() {
            @Override
            public void onToolCallOutput(ToolCallEndEvent event) {
                events.add(event);
            }
        };
        ToolDefinition<?> a = readOnlyTool("a", "a", Map.of("call", "a"));
        ToolDefinition<?> b = readOnlyTool("b", "b", Map.of("call", "b"));
        var context = ToolExecutionContext.builder().toolRegistry(new ToolRegistry(List.of(a, b)))
                .runtimeEventPublisher(new RuntimeEventPublisher(List.of(listener))).build();
        ToolExecuteCommand command = new ToolExecuteCommand(
                List.of(new ToolCallRequest("call-a", "a", "{}"), new ToolCallRequest("call-b", "b", "{}")),
                "exec-1", workspace(), null, List.of("a", "b"), UUID.randomUUID());

        try (DefaultToolExecutionManager manager = newManager(context)) {
            List<ToolExecuteResult> results = manager.execute(command);

            assertEquals(2, results.size());
            assertEquals(2, events.size());
            for (ToolCallEndEvent event : events) {
                String expected = event.getRequestId().equals("call-a") ? "a" : "b";
                assertEquals(expected, event.eventMetaData().get("call"));
            }
        }
    }

    @Test
    void nullValueInToolMetaDataDegradesToErrorResult() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("nullable", null);
        ToolExecuteResult result = executeReturning(ToolExecuteResult.success("ok", ToolResultType.NORMAL, meta),
                Map.of()).result();

        assertFalse(result.isSuccess());
        assertNotNull(result.getToolOutput());
        assertTrue(result.getToolOutput().startsWith("Tool execution error"));
    }

    private record MetaOutcome(ToolExecuteResult result, ToolCallEndEvent event) {
    }

    private MetaOutcome executeReturning(ToolExecuteResult toolResult, Map<String, Object> eventMetaData) {
        Queue<ToolCallEndEvent> events = new ConcurrentLinkedQueue<>();
        RuntimeListener listener = new RuntimeListener() {
            @Override
            public void onToolCallOutput(ToolCallEndEvent event) {
                events.add(event);
            }
        };
        ToolDefinition<?> tool = ToolDefinition.builder().id("meta").name("meta").maxOutput(100)
                .timeout(0L).concurrentPolicy(ConcurrentPolicy.SERIAL_MUTATION)
                .executor(execution -> toolResult).build();
        var context = ToolExecutionContext.builder().toolRegistry(new ToolRegistry(List.of(tool)))
                .runtimeEventPublisher(new RuntimeEventPublisher(List.of(listener))).build();
        ToolExecuteCommand command = new ToolExecuteCommand(List.of(new ToolCallRequest("call-1", "meta", "{}")),
                "exec-1", workspace(), null, eventMetaData, List.of("meta"), UUID.randomUUID(), false, null);

        try (DefaultToolExecutionManager manager = newManager(context)) {
            List<ToolExecuteResult> results = manager.execute(command);
            assertEquals(1, results.size());
            assertEquals(1, events.size());
            return new MetaOutcome(results.getFirst(), events.peek());
        }
    }

    private ToolDefinition<?> readOnlyTool(String id, String name, Map<String, Object> metaData) {
        return ToolDefinition.builder().id(id).name(name).maxOutput(100)
                .timeout(1L).concurrentPolicy(ConcurrentPolicy.READ_ONLY)
                .executor(execution -> ToolExecuteResult.success("ok", ToolResultType.NORMAL, metaData)).build();
    }

    private DefaultToolExecutionManager newManager(ToolExecutionContext context) {
        return new DefaultToolExecutionManager(context,
                invocation -> invocation.getMethod().invoke(invocation.getTarget(), invocation.getContext()),
                List.of());
    }

    private Workspace workspace() {
        return new Workspace() {
            @Override
            public String id() {
                return "workspace-1";
            }

            @Override
            public RuntimeEnvironment runtimeEnvironment() {
                return null;
            }

            @Override
            public String workDir() {
                return ".";
            }

            @Override
            public Path resolve(String path) {
                return Path.of(path);
            }
        };
    }
}
