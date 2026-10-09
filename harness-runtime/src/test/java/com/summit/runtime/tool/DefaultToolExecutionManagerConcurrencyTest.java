package com.summit.runtime.tool;

import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.runtime.RuntimeEnvironment;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.tool.ConcurrentPolicy;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecuteCommand;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecutionContext;
import com.summit.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards the batch scheduler's core invariant: every requested call runs exactly once, whatever the
 * concurrency shape.
 *
 * <p>Covers a production regression: a batch of two READ_ONLY calls (search_tool) executed twice, so
 * the transcript held two tool messages per call. The provider then rejected the next request with
 * "Messages with role 'tool' must be a response to a preceding message with 'tool_calls'".</p>
 */
class DefaultToolExecutionManagerConcurrencyTest {

    /** The exact production shape: a batch that fits inside the limit, and is fully concurrent. */
    @Test
    void concurrentBatchFittingInsideLimitRunsEachCallOnce() {
        Map<String, AtomicInteger> runs = new ConcurrentHashMap<>();
        ToolDefinition<?> tool = readOnlyTool("search_tool", runs);

        try (DefaultToolExecutionManager manager = manager(context(tool, 5))) {
            List<ToolExecuteResult> results = manager.execute(command("search_tool", 2));

            assertEquals(List.of("call-0", "call-1"), idsOf(results));
            assertEquals(1, runs.get("call-0").get());
            assertEquals(1, runs.get("call-1").get());
        }
    }

    /** A batch larger than the limit: the concurrent window plus the serial overflow, still once each. */
    @Test
    void concurrentBatchExceedingLimitRunsEachCallOnce() {
        Map<String, AtomicInteger> runs = new ConcurrentHashMap<>();
        ToolDefinition<?> tool = readOnlyTool("search_tool", runs);

        try (DefaultToolExecutionManager manager = manager(context(tool, 2))) {
            List<ToolExecuteResult> results = manager.execute(command("search_tool", 6));

            assertEquals(6, results.size());
            runs.forEach((callId, count) ->
                    assertEquals(1, count.get(), "call " + callId + " must run exactly once"));
        }
    }

    /** A serial batch must not consult the concurrency limit; an absent setting is not a failure. */
    @Test
    void serialBatchRunsEachCallOnceWithoutConcurrencyLimit() {
        Map<String, AtomicInteger> runs = new ConcurrentHashMap<>();
        ToolDefinition<?> tool = serialTool("edit", runs);

        try (DefaultToolExecutionManager manager = manager(context(tool, null))) {
            List<ToolExecuteResult> results = manager.execute(command("edit", 2));

            assertEquals(List.of("call-0", "call-1"), idsOf(results));
            assertEquals(1, runs.get("call-0").get());
            assertEquals(1, runs.get("call-1").get());
        }
    }

    private ToolDefinition<?> readOnlyTool(String name, Map<String, AtomicInteger> runs) {
        return ToolDefinition.builder()
                .id(name).name(name).maxOutput(1000).timeout(10L)
                .concurrentPolicy(ConcurrentPolicy.READ_ONLY)
                .executor(execution -> {
                    runs.computeIfAbsent(execution.getId(), key -> new AtomicInteger()).incrementAndGet();
                    return ToolExecuteResult.success("ok");
                })
                .build();
    }

    private ToolDefinition<?> serialTool(String name, Map<String, AtomicInteger> runs) {
        return ToolDefinition.builder()
                .id(name).name(name).maxOutput(1000).timeout(0L)
                .concurrentPolicy(ConcurrentPolicy.SERIAL_MUTATION)
                .executor(execution -> {
                    runs.computeIfAbsent(execution.getId(), key -> new AtomicInteger()).incrementAndGet();
                    return ToolExecuteResult.success("ok");
                })
                .build();
    }

    private ToolExecutionContext context(ToolDefinition<?> tool, Integer concurrentToolLimit) {
        return ToolExecutionContext.builder()
                .toolRegistry(new ToolRegistry(List.of(tool)))
                .runtimeEventPublisher(new RuntimeEventPublisher(List.of()))
                .concurrentToolLimit(concurrentToolLimit)
                .build();
    }

    private DefaultToolExecutionManager manager(ToolExecutionContext context) {
        return new DefaultToolExecutionManager(
                context,
                invocation -> invocation.getMethod().invoke(invocation.getTarget(), invocation.getContext()),
                List.of());
    }

    private ToolExecuteCommand command(String toolName, int calls) {
        List<ToolCallRequest> requests = IntStream.range(0, calls)
                .mapToObj(index -> new ToolCallRequest("call-" + index, toolName, index, "{}"))
                .toList();
        return new ToolExecuteCommand(requests, "execution-1", workspace(), null, List.of(toolName), "1234567890123456789");
    }

    private List<String> idsOf(List<ToolExecuteResult> results) {
        List<String> ids = new ArrayList<>();
        for (ToolExecuteResult result : results) {
            ids.add(result.getId());
        }
        return ids;
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
