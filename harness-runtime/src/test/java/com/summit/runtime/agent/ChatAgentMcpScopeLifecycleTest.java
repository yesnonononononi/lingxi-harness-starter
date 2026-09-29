package com.summit.runtime.agent;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.agent.ExecutionState;
import com.summit.core.conf.McpConfig;
import com.summit.core.conf.McpTransport;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.mcp.McpRegister;
import com.summit.core.mcp.McpSession;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.model.RequestModelInvokerFactory;
import com.summit.core.runtime.RuntimeFactory;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecutor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The MCP scope belongs to the execution, not to one attempt: a resume reuses the scope its
 * suspension kept, and only a terminal state releases it.
 */
class ChatAgentMcpScopeLifecycleTest {

    private final AtomicInteger opened = new AtomicInteger();
    private final AtomicInteger closed = new AtomicInteger();

    @Test
    void resumeReusesTheScopeKeptByTheSuspension() {
        TestAgent agent = agent(ExecutionState.SUSPENDED);

        agent.execute(request("e-1"));
        agent.execute(request("e-1"));

        assertEquals(1, opened.get(), "the second attempt must not open a new session");
        assertEquals(0, closed.get(), "a suspension keeps its scope");
    }

    @Test
    void terminalStateReleasesTheScope() {
        TestAgent agent = agent(ExecutionState.COMPLETED);

        agent.execute(request("e-1"));

        assertEquals(1, closed.get());
    }

    @Test
    void aRequestWithoutMcpNeverOpensASession() {
        TestAgent agent = agent(ExecutionState.COMPLETED);

        agent.execute(Execution.builder()
                .id("e-2").agentId("a")
                .messages(new ArrayList<>(List.of(UserMessageEntity.from("task"))))
                .agentRequest(AgentRequest.builder()
                        .messages(List.of(UserMessageEntity.from("task")))
                        .build())
                .build());

        assertEquals(0, opened.get());
        assertEquals(0, closed.get());
    }

    /**
     * A request that declares servers but runs on an agent without a register must not fail the
     * request, and must not silently pretend everything is fine either: the empty scope is a
     * configuration fault worth warning about.
     */
    @Test
    void declaredServersWithoutARegisterDegradeToEmptyScope() {
        TestAgent agent = agentWithoutRegister(ExecutionState.COMPLETED);

        agent.execute(request("e-3"));

        assertEquals(0, opened.get(), "no register means no session can be opened");
        assertEquals(0, closed.get());
    }

    private TestAgent agent(ExecutionState terminalState) {
        McpRegister register = config -> {
            opened.incrementAndGet();
            return McpToolScope.of(List.of(session()));
        };
        return new TestAgent(factory(terminalState), register);
    }

    private TestAgent agentWithoutRegister(ExecutionState terminalState) {
        return new TestAgent(factory(terminalState), null);
    }

    private static RuntimeFactory factory(ExecutionState terminalState) {
        return (invoker, workspace, scope) -> execution -> {
            execution.setExecutionState(terminalState);
            return execution;
        };
    }

    private McpSession session() {
        return new McpSession() {
            public String name() { return "test-server"; }

            public List<ToolDefinition<? extends ToolExecutor>> tools() { return List.of(); }

            public void close() { closed.incrementAndGet(); }
        };
    }

    private static Execution request(String executionId) {
        return Execution.builder()
                .id(executionId).agentId("a")
                .messages(new ArrayList<>(List.of(UserMessageEntity.from("task"))))
                .agentRequest(AgentRequest.builder()
                        .messages(List.of(UserMessageEntity.from("task")))
                        .mcpConfig(mcpConfig())
                        .build())
                .build();
    }

    private static McpConfig mcpConfig() {
        McpConfig config = new McpConfig();
        config.setMcp(List.of(new McpConfig.MCP("test-server", McpTransport.STREAMABLE_HTTP,
                new McpConfig.StreamableHttp("https://test.example/mcp", Map.of(),
                        Duration.ofSeconds(3), Duration.ofSeconds(4)),
                null, 100)));
        return config;
    }

    /** Minimal agent: no model, no workspace, only the MCP scope lifecycle under test. */
    private static final class TestAgent extends ChatAgent {
        private TestAgent(RuntimeFactory factory, McpRegister register) {
            super(factory, null, null, null, register);
        }

        @Override
        public String id() {
            return "test-agent";
        }

        @Override
        protected Workspace resolveWorkspace(AgentRequest request) {
            return null;
        }

        @Override
        protected RequestModelInvokerFactory.Selection findSelection(AgentRequest agentRequest) {
            return new RequestModelInvokerFactory.Selection(null, null, false);
        }
    }
}
