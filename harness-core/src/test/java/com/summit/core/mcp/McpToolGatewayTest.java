package com.summit.core.mcp;

import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.tool.ConcurrentPolicy;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecutor;
import com.summit.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The gateway's argument contract and its two refusals.
 *
 * <p>Rewriting is the whole mechanism: {@link McpToolGateway#unwrap} turns one call into the call of
 * the remote tool it names, keeping the caller's identity, and answers the call unchanged whenever it
 * names nothing callable — the gateway definition then reports the reason. Both halves have to hold,
 * or a name the model made up would either be forwarded to a tool that does not exist or be lost.</p>
 */
class McpToolGatewayTest {

    private static final String REMOTE = "mcp_github_get_me";

    @Test
    void aGatewayCallBecomesTheCallOfTheRemoteToolItNames() {
        ToolCallRequest call = call("{\"name\":\"" + REMOTE + "\",\"arguments\":{\"q\":\"hello\"}}");

        ToolCallRequest unwrapped = McpToolGateway.unwrap(call, scopeOf(remote(REMOTE)), registry());

        assertEquals(REMOTE, unwrapped.name());
        assertEquals("{\"q\":\"hello\"}", unwrapped.arguments());
        assertEquals(call.id(), unwrapped.id(), "the call identity is what ties the events together");
        assertEquals(call.requestIndex(), unwrapped.requestIndex());
    }

    @Test
    void aCallOfAnyOtherToolIsReturnedUntouched() {
        ToolCallRequest call = new ToolCallRequest("call-1", "read_file", 2, "{\"path\":\"a.txt\"}");

        assertSame(call, McpToolGateway.unwrap(call, scopeOf(remote(REMOTE)), registry()));
        assertNull(McpToolGateway.unwrap(null, scopeOf(remote(REMOTE)), registry()));
    }

    @Test
    void aMissingArgumentsFieldBecomesAnEmptyObject() {
        ToolCallRequest unwrapped = McpToolGateway.unwrap(
                call("{\"name\":\"" + REMOTE + "\"}"), scopeOf(remote(REMOTE)), registry());

        assertEquals(REMOTE, unwrapped.name());
        assertEquals("{}", unwrapped.arguments(), "a tool without parameters must not be called with null");
    }

    @Test
    void aDoubleEncodedArgumentsStringIsAcceptedAsItsObject() {
        // Models stringify the nested object now and then; the remote tool still wants the object.
        ToolCallRequest unwrapped = McpToolGateway.unwrap(
                call("{\"name\":\"" + REMOTE + "\",\"arguments\":\"{\\\"q\\\":\\\"hello\\\"}\"}"),
                scopeOf(remote(REMOTE)), registry());

        assertEquals(REMOTE, unwrapped.name());
        assertEquals("{\"q\":\"hello\"}", unwrapped.arguments());
    }

    @Test
    void aNameThatIsNotARemoteToolIsRefusedWithAReasonForTheModel() {
        assertRejected(call("{\"name\":\"mcp_absent\",\"arguments\":{}}"), "use search_tool");
        assertRejected(call("{\"arguments\":{}}"), "requires \"name\"");
        assertRejected(call("{\"name\":\"  \",\"arguments\":{}}"), "requires \"name\"");
        assertRejected(call("not json at all"), "expects a JSON object");
        assertRejected(call("[1,2]"), "expects a JSON object");
    }

    @Test
    void aBuiltInToolCannotBeReachedThroughTheGateway() {
        // Otherwise the gateway would be a way around the static whitelist.
        assertRejected(call("{\"name\":\"read_file\",\"arguments\":{}}"), "is a built-in tool");
    }

    @Test
    void theGatewayCannotCallItself() {
        assertRejected(call("{\"name\":\"" + McpToolGateway.NAME + "\",\"arguments\":{}}"), "cannot call itself");
    }

    @Test
    void aRefusedArgumentsValueCarriesTheTargetSchemaSoTheModelCanCorrectItself() {
        // The schema may have been pruned out of the conversation by then, so the refusal repeats it.
        String reason = ((McpToolGateway.Rejected) McpToolGateway.resolve(
                "{\"name\":\"" + REMOTE + "\",\"arguments\":5}", scopeOf(remote(REMOTE)), registry())).reason();

        assertTrue(reason.contains("\"arguments\""), reason);
        assertTrue(reason.contains("{\"type\":\"object\"}"), reason);
    }

    @Test
    void aRefusalIsNeverSilent() {
        // Every branch of the contract must produce a sentence, since it is the model's only hint.
        for (String arguments : List.of("", "{}", "{\"name\":\"absent\",\"arguments\":{}}",
                "{\"name\":\"" + McpToolGateway.NAME + "\"}")) {
            assertInstanceOf(McpToolGateway.Rejected.class,
                    McpToolGateway.resolve(arguments, McpToolScope.EMPTY, registry()), arguments);
        }
    }

    @Test
    void theGatewayIsExposedByNamingItOrTheLookupEntry() {
        assertTrue(McpToolGateway.admittedBy(List.of(McpToolGateway.NAME)));
        assertTrue(McpToolGateway.admittedBy(List.of(McpToolGateway.DISCOVERY_TOOL)));
        assertTrue(McpToolGateway.admittedBy(List.of(" other ", McpToolGateway.DISCOVERY_TOOL)));
        assertFalse(McpToolGateway.admittedBy(List.of("read_file")));
        assertFalse(McpToolGateway.admittedBy(List.of()));
        assertFalse(McpToolGateway.admittedBy(null), "an absent list admits nothing, like any other tool");
    }

    @Test
    void theGatewayNameStaysOutsideTheRemoteNamespace() {
        // Remote tools are published as mcp_ plus their own name: living in that namespace means a
        // server with a tool of that name would collide with the gateway.
        assertFalse(McpToolGateway.NAME.startsWith("mcp_"), McpToolGateway.NAME);
        assertTrue(McpToolGateway.DISCOVERY_TOOL.startsWith("search"));
    }

    // --- fixtures --------------------------------------------------------------------------

    private static void assertRejected(ToolCallRequest call, String expectedFragment) {
        McpToolGateway.Resolution resolution = McpToolGateway.resolve(call.arguments(), scopeOf(remote(REMOTE)), registry());

        McpToolGateway.Rejected rejected = assertInstanceOf(McpToolGateway.Rejected.class, resolution);
        assertTrue(rejected.reason().contains(expectedFragment), rejected.reason());
        assertSame(call, McpToolGateway.unwrap(call, scopeOf(remote(REMOTE)), registry()),
                "a refused call stays as it is, so the gateway definition answers it");
    }

    private static ToolCallRequest call(String arguments) {
        return new ToolCallRequest("call-1", McpToolGateway.NAME, 3, arguments);
    }

    private static ToolRegistry registry() {
        return new ToolRegistry(List.of(definition("read_file")));
    }

    private static ToolDefinition<?> definition(String name) {
        AtomicBoolean called = new AtomicBoolean();
        return ToolDefinition.builder()
                .id(name).name(name).maxOutput(100).timeout(5L)
                .concurrentPolicy(ConcurrentPolicy.READ_ONLY)
                .parametersJsonSchema("{\"type\":\"object\"}")
                .executor(execution -> {
                    called.set(true);
                    return ToolExecuteResult.success("ok");
                })
                .build();
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
}
