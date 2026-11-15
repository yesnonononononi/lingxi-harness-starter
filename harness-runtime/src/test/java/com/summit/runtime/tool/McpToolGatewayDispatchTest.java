package com.summit.runtime.tool;

import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.event.ToolCallEndEvent;
import com.summit.core.conversation.event.ToolCallStartEvent;
import com.summit.core.mcp.McpSession;
import com.summit.core.mcp.McpToolGateway;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.runtime.RuntimeEnvironment;
import com.summit.core.runtime.RuntimeListener;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.tool.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A gateway call is rewritten at the execution entry, so everything downstream sees the remote tool.
 *
 * <p>These tests pin the whole contract: the remote executor runs with the inner arguments, runtime
 * events and the returned identity name the remote tool while keeping the caller's call id and
 * request index, and a call the run cannot rewrite is answered by the gateway definition instead of
 * being forwarded. Without the rewrite the design collapses into "the model calls a tool named
 * call_mcp_tool", which is exactly what the providers would cache away.</p>
 */
class McpToolGatewayDispatchTest {

    private static final String GATEWAY = McpToolGateway.NAME;
    private static final String REMOTE = "mcp_github_get_me";
    private static final String RESPONSE_ID = "1234567890123456789";

    @Test
    void aGatewayCallRunsTheRemoteToolWithTheInnerArguments() {
        AtomicReference<ToolExecution> seen = new AtomicReference<>();

        try (Fixture fixture = new Fixture(scopeOf(remoteTool(REMOTE, "remote", seen)))) {
            ToolExecuteResult result = fixture.run(List.of(McpToolGateway.NAME),
                    gatewayCall("call-1", 0, "{\"name\":\"" + REMOTE + "\",\"arguments\":{\"q\":\"hello\"}}")).getFirst();

            assertEquals("remote:{\"q\":\"hello\"}", result.getToolOutput());
            assertEquals(REMOTE, seen.get().getToolDefinition().name(), "the remote executor must be the one that ran");
            assertEquals("{\"q\":\"hello\"}", seen.get().getArgs(),
                    "the remote tool gets its own arguments, not the envelope");
        }
    }

    @Test
    void theCallerIdentitySurvivesTheRewriteOnEveryEvent() {
        try (Fixture fixture = new Fixture(scopeOf(remoteTool(REMOTE, "remote", new AtomicReference<>())))) {
            ToolExecuteResult result = fixture.run(List.of(McpToolGateway.NAME),
                    gatewayCall("call-9", 4, "{\"name\":\"" + REMOTE + "\",\"arguments\":{}}")).getFirst();

            assertEquals("call-9", result.getId());
            assertEquals(4, result.getRequestIndex());

            ToolCallStartEvent start = fixture.listener.starts.getFirst();
            assertEquals(REMOTE, start.getToolName(), "the start event names the tool that really runs");
            assertEquals("call-9", start.getRequestId());
            assertEquals(4, start.getRequestIndex());

            ToolCallEndEvent end = fixture.listener.ends.getFirst();
            assertEquals(REMOTE, end.getToolName());
            assertEquals("{}", end.getArgs(), "the end event shows the remote arguments, not the envelope");
            assertEquals("call-9", end.getRequestId());
            assertEquals(ToolCallStatus.COMPLETED, end.getResultStatus());
        }
    }

    @Test
    void everyCallOfABatchIsRewrittenSeparately() {
        try (Fixture fixture = new Fixture(scopeOf(
                remoteTool("mcp_a", "a", new AtomicReference<>()),
                remoteTool("mcp_b", "b", new AtomicReference<>())))) {
            List<ToolExecuteResult> results = fixture.run(List.of(McpToolGateway.NAME),
                    gatewayCall("call-a", 0, "{\"name\":\"mcp_a\",\"arguments\":{}}"),
                    gatewayCall("call-b", 1, "{\"name\":\"mcp_b\",\"arguments\":{}}"));

            assertEquals(List.of("call-a", "call-b"), results.stream().map(ToolExecuteResult::getId).toList());
            assertEquals(List.of("a:{}", "b:{}"), results.stream().map(ToolExecuteResult::getToolOutput).toList());
            assertEquals(2, fixture.listener.starts.size(), "one start event per call, no retries");
        }
    }

    @Test
    void aGatewayCallOfARunThatCannotReachItIsNotRewritten() {
        AtomicReference<ToolExecution> seen = new AtomicReference<>();

        // The whitelist names neither the gateway nor the lookup entry, so this run could not have
        // produced the call, and a remote tool must not become reachable through it.
        try (Fixture fixture = new Fixture(scopeOf(remoteTool(REMOTE, "remote", seen)))) {
            ToolExecuteResult result = fixture.run(List.of("something_else"),
                    gatewayCall("call-1", 0, "{\"name\":\"" + REMOTE + "\",\"arguments\":{}}")).getFirst();

            assertTrue(result.getToolOutput().startsWith("Tool '" + GATEWAY + "' is not allowed"),
                    result.getToolOutput());
            assertNull(seen.get(), "the remote tool must not run");
            assertEquals(0, fixture.gatewayCalls.get(), "an unreachable gateway must not even run its executor");
        }
    }

    @Test
    void namingOnlyTheLookupEntryDoesNotReachTheGateway() {
        AtomicReference<ToolExecution> seen = new AtomicReference<>();

        // Discovery and calling are two separate names: whitelisting the lookup allows looking a tool
        // up, not calling it. Nothing here implies the other.
        try (Fixture fixture = new Fixture(scopeOf(remoteTool(REMOTE, "remote", seen)))) {
            ToolExecuteResult result = fixture.run(List.of(McpToolGateway.DISCOVERY_TOOL),
                    gatewayCall("call-1", 0, "{\"name\":\"" + REMOTE + "\",\"arguments\":{}}")).getFirst();

            assertTrue(result.getToolOutput().startsWith("Tool '" + GATEWAY + "' is not allowed"),
                    result.getToolOutput());
            assertNull(seen.get(), "the lookup entry must not become a way to call a remote tool");
            assertEquals(0, fixture.gatewayCalls.get());
        }
    }

    @Test
    void anUnresolvableNameIsAnsweredByTheGatewayDefinition() {
        try (Fixture fixture = new Fixture(McpToolScope.EMPTY)) {
            String envelope = "{\"name\":\"mcp_missing\",\"arguments\":{}}";

            ToolExecuteResult result = fixture.run(List.of(McpToolGateway.NAME),
                    gatewayCall("call-1", 0, envelope)).getFirst();

            assertEquals("gateway saw the raw envelope", result.getToolOutput(),
                    "the gateway executor is what turns an unresolvable call into a reason");
            assertEquals(1, fixture.gatewayCalls.get());
            assertEquals(envelope, fixture.gatewayArgs.get(),
                    "the envelope is handed over untouched, so the reason can name the missing tool");
        }
    }

    // --- fixtures --------------------------------------------------------------------------

    /** One manager, one scope and the run's recordings; closing it releases the tool threads. */
    private static final class Fixture implements AutoCloseable {
        private final McpToolScope scope;
        private final DefaultToolExecutionManager manager;
        private final Listener listener = new Listener();
        private final AtomicInteger gatewayCalls = new AtomicInteger();
        private final AtomicReference<String> gatewayArgs = new AtomicReference<>();

        private Fixture(McpToolScope scope) {
            this.scope = scope;
            ToolExecutionContext context = ToolExecutionContext.builder()
                    .toolRegistry(new ToolRegistry(List.of(gatewayDefinition())))
                    .runtimeEventPublisher(new RuntimeEventPublisher(List.of(listener)))
                    .build();
            this.manager = new DefaultToolExecutionManager(context,
                    invocation -> invocation.getMethod().invoke(invocation.getTarget(), invocation.getContext()),
                    List.of());
        }

        private List<ToolExecuteResult> run(List<String> allowedTools, ToolCallRequest... requests) {
            return manager.execute(ToolExecuteCommand.builder()
                    .requests(List.of(requests))
                    .executionId("execution-1")
                    .responseId(RESPONSE_ID)
                    .workspace(workspace())
                    .mcpToolScope(scope)
                    .allowedTools(allowedTools)
                    .build());
        }

        /** Stands in for the production definition; recording proves its executor was reached. */
        private ToolDefinition<?> gatewayDefinition() {
            return ToolDefinition.builder()
                    .id(GATEWAY).name(GATEWAY).maxOutput(1000).timeout(0L)
                    .concurrentPolicy(ConcurrentPolicy.SERIAL_MUTATION)
                    .executor(execution -> {
                        gatewayCalls.incrementAndGet();
                        gatewayArgs.set(execution.getArgs());
                        return ToolExecuteResult.success("gateway saw the raw envelope");
                    })
                    .build();
        }

        @Override
        public void close() {
            manager.close();
        }
    }

    private static ToolCallRequest gatewayCall(String id, int index, String arguments) {
        return new ToolCallRequest(id, GATEWAY, index, arguments);
    }

    /** Both remote tools and the gateway declare themselves workspace tools, as the production ones do. */
    private static Workspace workspace() {
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

    private static McpToolScope scopeOf(ToolDefinition<?>... tools) {
        List<ToolDefinition<? extends ToolExecutor>> scoped = List.of(tools);
        return McpToolScope.of(List.of(new McpSession() {
            @Override
            public String name() {
                return "test-server";
            }

            @Override
            public List<ToolDefinition<? extends ToolExecutor>> tools() {
                return scoped;
            }

            @Override
            public void close() {
            }
        }));
    }

    private static ToolDefinition<?> remoteTool(String name, String output, AtomicReference<ToolExecution> seen) {
        return ToolDefinition.builder()
                .id(name).name(name).maxOutput(1000).timeout(0L)
                // A remote tool's side effects are unknown, so it never joins a concurrent batch.
                .concurrentPolicy(ConcurrentPolicy.SERIAL_MUTATION)
                .executor(execution -> {
                    seen.set(execution);
                    return ToolExecuteResult.success(output + ":" + execution.getArgs());
                })
                .build();
    }

    private static final class Listener implements RuntimeListener {
        private final List<ToolCallStartEvent> starts = new CopyOnWriteArrayList<>();
        private final List<ToolCallEndEvent> ends = new CopyOnWriteArrayList<>();

        @Override
        public int order() {
            return 0;
        }

        @Override
        public void onToolCall(ToolCallStartEvent event) {
            starts.add(event);
        }

        @Override
        public void onToolCallOutput(ToolCallEndEvent event) {
            ends.add(event);
        }
    }
}
