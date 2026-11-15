package com.summit.kernel.tools.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.mcp.McpSession;
import com.summit.core.mcp.McpToolGateway;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.tool.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The gateway definition and the reason it hands back.
 *
 * <p>A resolvable call never reaches this executor — the execution entry rewrites it into the remote
 * tool's own call. What is left is the refusal path, and it is the model's only feedback: the reason
 * must name the way out (the lookup entry), the schema of a mistyped argument object, and the fact
 * that a built-in is not reachable through the gateway.</p>
 */
class CallMcpToolExecutorTest {

    private static final String REMOTE = "mcp_github_get_me";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CallMcpToolExecutor executor = new CallMcpToolExecutor(() -> registry());

    @Test
    void anUnknownNameIsAnsweredWithTheWayToFindTheRealOne() {
        ToolExecuteResult result = executor.execute(execution(
                "{\"name\":\"mcp_absent\",\"arguments\":{}}", McpToolScope.EMPTY));

        assertFalse(result.isSuccess());
        assertTrue(result.getToolOutput().contains("unknown MCP tool 'mcp_absent'"), result.getToolOutput());
        assertTrue(result.getToolOutput().contains(McpToolGateway.DISCOVERY_TOOL), result.getToolOutput());
    }

    @Test
    void aBuiltInToolIsRefusedInsteadOfForwarded() {
        ToolExecuteResult result = executor.execute(execution(
                "{\"name\":\"read_file\",\"arguments\":{}}", McpToolScope.EMPTY));

        assertFalse(result.isSuccess());
        assertTrue(result.getToolOutput().contains("is a built-in tool"), result.getToolOutput());
    }

    @Test
    void aMistypedArgumentsObjectIsAnsweredWithTheTargetSchema() {
        ToolExecuteResult result = executor.execute(execution(
                "{\"name\":\"" + REMOTE + "\",\"arguments\":\"oops\"}", scopeOf(remote(REMOTE))));

        assertFalse(result.isSuccess());
        assertTrue(result.getToolOutput().contains("{\"type\":\"object\"}"), result.getToolOutput());
    }

    @Test
    void aResolvableEnvelopeIsNotCalledFromHere() {
        // Only the runtime forwards a resolved call; reaching this branch means someone bypassed it,
        // and silently calling the remote tool twice would be far worse than saying so.
        ToolExecuteResult result = executor.execute(execution(
                "{\"name\":\"" + REMOTE + "\",\"arguments\":{}}", scopeOf(remote(REMOTE))));

        assertFalse(result.isSuccess());
        assertTrue(result.getToolOutput().contains("on its own"), result.getToolOutput());
        assertTrue(result.getToolOutput().contains(REMOTE), result.getToolOutput());
    }

    @Test
    void withoutARegistryEveryRemoteNameIsStillRefusedRatherThanCrashing() {
        CallMcpToolExecutor registryless = new CallMcpToolExecutor(() -> null);

        ToolExecuteResult result = registryless.execute(execution(
                "{\"name\":\"" + REMOTE + "\",\"arguments\":{}}", scopeOf(remote(REMOTE))));

        assertFalse(result.isSuccess());
        assertTrue(result.getToolOutput().contains("on its own"), result.getToolOutput());
    }

    @Test
    void theRegisteredDefinitionCarriesAStaticSchemaAndASerialPolicy() throws Exception {
        ToolDefinition<CallMcpToolExecutor> definition = new CallMcpToolAutoConfiguration()
                .callMcpToolDefinition(provider(registry()));

        assertEquals(CallMcpToolExecutor.NAME, definition.name());
        assertEquals(McpToolGateway.NAME, definition.name());
        assertEquals(ConcurrentPolicy.SERIAL_MUTATION, definition.concurrentPolicy(),
                "a remote tool's side effects are unknown, so a gateway call never runs concurrently");

        // The declaration sits at the head of the prompt: nothing about the run may leak into it.
        JsonNode schema = objectMapper.readTree(definition.parametersJsonSchema());
        assertEquals("object", schema.get("type").asText());
        assertEquals("name", schema.get("required").get(0).asText());
        assertTrue(schema.get("properties").has(McpToolGateway.TOOL_NAME_FIELD));
        assertTrue(schema.get("properties").has(McpToolGateway.ARGUMENTS_FIELD));
        // Nothing about the run may leak in: a per-run description would move the prompt's head.
        assertTrue(definition.description().contains(McpToolGateway.DISCOVERY_TOOL),
                "the description must point at the lookup entry");
    }

    // --- fixtures --------------------------------------------------------------------------

    private static ToolExecution execution(String args, McpToolScope scope) {
        return ToolExecution.builder()
                .id("call-1")
                .executionId("execution-1")
                .args(args)
                .mcpToolScope(scope)
                .build();
    }

    private static ToolRegistry registry() {
        return new ToolRegistry(List.of(ToolDefinition.builder()
                .id("read_file").name("read_file").maxOutput(100).timeout(5L)
                .concurrentPolicy(ConcurrentPolicy.READ_ONLY)
                .executor(execution -> ToolExecuteResult.success("ok"))
                .build()));
    }

    private static ToolDefinition<?> remote(String name) {
        return ToolDefinition.builder()
                .id(name).name(name).maxOutput(100).timeout(5L)
                .concurrentPolicy(ConcurrentPolicy.SERIAL_MUTATION)
                .parametersJsonSchema("{\"type\":\"object\"}")
                .executor(execution -> ToolExecuteResult.success("ok"))
                .build();
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

    private static ObjectProvider<ToolRegistry> provider(ToolRegistry registry) {
        return new ObjectProvider<>() {
            @Override
            public ToolRegistry getObject() {
                return registry;
            }

            @Override
            public ToolRegistry getObject(Object... args) {
                return registry;
            }

            @Override
            public ToolRegistry getIfAvailable() {
                return registry;
            }

            @Override
            public ToolRegistry getIfUnique() {
                return registry;
            }
        };
    }
}
