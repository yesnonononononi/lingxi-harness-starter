package com.summit.runtime.toolSupport;

import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.runtime.*;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.tool.*;
import com.summit.runtime.DefaultInterceptorProcessor;
import com.summit.runtime.configs.CommonToolConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ToolWhitelistTest {

    @Test
    void rejectsApplicationToolOutsideRequestWhitelist() {
        DefaultToolExecutionManager manager = manager(tool("custom_tool"));
        ToolExecuteResult result = manager.execute(command(List.of("another_tool"))).getFirst();

        assertFalse(result.isSuccess());
        assertTrue(result.getToolOutput().contains("not allowed for this agent request"));
    }

    @Test
    void emptyWhitelistRejectsEveryToolIncludingRuntimeProvidedOnes() {
        DefaultToolExecutionManager manager = manager(tool("internal_io_name"));
        ToolExecuteResult result = manager.execute(command(List.of())).getFirst();

        assertFalse(result.isSuccess());
        assertTrue(result.getToolOutput().contains("not allowed for this agent request"));
    }

    @Test
    void executionPolicyRunsOnCallerThreadBeforeToolExecutionTimeout() {
        Thread caller = Thread.currentThread();
        AtomicReference<Thread> policyThread = new AtomicReference<>();
        ToolExecutionPolicy policy = execution -> {
            policyThread.set(Thread.currentThread());
            return ToolExecuteResult.err("approval rejected");
        };
        DefaultToolExecutionManager manager = manager(tool("custom_tool"), List.of(policy));

        ToolExecuteResult result = manager.execute(command(null)).getFirst();

        assertSame(caller, policyThread.get());
        assertEquals("approval rejected", result.getToolOutput());
    }

    private static ToolDefinition<ToolExecutor> tool(String name) {
        return ToolDefinition.<ToolExecutor>builder()
                .id(name).name(name).description("test").parametersJsonSchema("{}")
                .maxOutput(100).timeout(1L).readOnly(true)
                .executor(execution -> ToolExecuteResult.success("ok"))
                .build();
    }

    private static DefaultToolExecutionManager manager(ToolDefinition<?> definition) {
        return manager(definition, List.of());
    }

    private static DefaultToolExecutionManager manager(ToolDefinition<?> definition,
                                                       List<ToolExecutionPolicy> policies) {
        ToolRegistry registry = new ToolRegistry(new ArrayList<>());
        registry.register(definition.name(), definition);
        return new DefaultToolExecutionManager(
                ToolExecutionContext.builder()
                        .toolRegistry(registry)
                        .runtimeEventPublisher(new RuntimeEventPublisher(List.of()))
                        .build(),
                new DefaultInterceptorProcessor<ToolInterceptor, ToolExecution>(new ArrayList<>()),
                CommonToolConfig.builder().maxToolOutputDisplay(100).build(), policies);
    }

    private static ToolExecuteCommand command(List<String> whitelist) {
        ToolCallRequest call = new ToolCallRequest("call-1", "custom_tool", "{}");
        if (whitelist != null && whitelist.isEmpty()) {
            call = new ToolCallRequest("call-1", "internal_io_name", "{}");
        }
        return new ToolExecuteCommand(List.of(call), "exec-1", "session-1",
                new StubWorkspace(), null, LoopBoundary.EXECUTE, whitelist);
    }

    private static final class StubWorkspace implements Workspace {
        @Override public String id() { return "test"; }
        @Override public RuntimeEnvironment runtimeEnvironment() { return null; }
        @Override public String workDir() { return "."; }
        @Override public Path resolve(String path) { return Path.of(path); }
    }
}
