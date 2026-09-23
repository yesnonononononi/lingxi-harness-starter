package com.summit.runtime.toolSupport;

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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultToolExecutionManagerPromiseTest {

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
                    List.of(new ToolCallRequest("call-1", "choice", "{}", null)),
                    "execution-1",
                    workspace(),
                    null,
                    null));

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
