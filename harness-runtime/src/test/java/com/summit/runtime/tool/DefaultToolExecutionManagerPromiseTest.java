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
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultToolExecutionManagerPromiseTest {

    @Test
    void policySuccessBypassesExecutor() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        ToolDefinition<?> tool = ToolDefinition.builder().id("write").name("write").maxOutput(100)
                .timeout(0L).concurrentPolicy(ConcurrentPolicy.SERIAL_MUTATION)
                .executor(execution -> { calls.incrementAndGet(); return ToolExecuteResult.success("written"); }).build();
        var context = ToolExecutionContext.builder().toolRegistry(new ToolRegistry(List.of(tool)))
                .runtimeEventPublisher(new RuntimeEventPublisher(List.of())).build();
        // The whitelist is an allow-list with no implicit default, so the tool under test is named
        // explicitly — this case is about policy bypass, not about admission.
        ToolExecuteCommand command = new ToolExecuteCommand(List.of(new ToolCallRequest("c", "write", "{}")),
                "e", workspace(), null, List.of("write"), UUID.randomUUID());
        for (boolean bypass : List.of(true, false)) {
            List<com.summit.core.tool.ToolExecutionPolicy> policies = bypass
                    ? List.of(execution -> ToolExecuteResult.success("cached")) : List.of();
            try (var manager = new DefaultToolExecutionManager(context,
                    invocation -> invocation.getMethod().invoke(invocation.getTarget(), invocation.getContext()), policies)) {
                assertEquals(bypass ? "cached" : "written", manager.execute(command).getFirst().getToolOutput());
            }
        }
        assertEquals(1, calls.get());
    }

    @Test
    void preservesPromiseAndPublishesPromisedStatus() {
        AtomicReference<ToolCallStatus> publishedStatus = new AtomicReference<>();
        RuntimeListener listener = new RuntimeListener() {
            @Override
            public void onToolCallOutput(ToolCallEndEvent event) {
                publishedStatus.set(event.resultStatus());
            }
        };
        ToolDefinition<?> definition = ToolDefinition.builder()
                .id("choice")
                .name("choice")
                .maxOutput(1000)
                .timeout(0L)
                .concurrentPolicy(ConcurrentPolicy.SERIAL_MUTATION)
                .executor(execution -> ToolExecuteResult.err("executor must not be invoked"))
                .build();
        ToolRegistry registry = new ToolRegistry(List.of(definition));
        ToolExecutionContext context = ToolExecutionContext.builder()
                .toolRegistry(registry)
                .runtimeEventPublisher(new RuntimeEventPublisher(List.of(listener)))
                .build();

        try (DefaultToolExecutionManager manager = new DefaultToolExecutionManager(
                context,
                invocation -> invocation.getMethod().invoke(invocation.getTarget(), invocation.getContext()),
                List.of(execution -> ToolExecuteResult.promise("waiting")))) {
            List<ToolExecuteResult> results = manager.execute(new ToolExecuteCommand(
                    List.of(new ToolCallRequest("call-1", "choice", "{}")),
                    "execution-1",
                    workspace(),
                    null,
                    List.of("choice"), UUID.randomUUID()));

            assertEquals(1, results.size());
            assertTrue(results.getFirst().isPromise());
            assertEquals("call-1", results.getFirst().getId());
            assertEquals(ToolCallStatus.PROMISED, publishedStatus.get());
        }
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
