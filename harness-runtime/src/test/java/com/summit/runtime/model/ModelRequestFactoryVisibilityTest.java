package com.summit.runtime.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.ContextSpec;
import com.summit.core.agent.Execution;
import com.summit.core.conversation.ConversationManager;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.conversation.message.TokenUsageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.mcp.McpSession;
import com.summit.core.mcp.McpToolGateway;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.model.ModelChatCommand;
import com.summit.core.runtime.loop.LoopMessages;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.tool.*;
import com.summit.runtime.context.RuntimeContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Model-visible tools = the whitelisted static tools, plus the gateway when the whitelist exposes
 * it — and nothing else. The request's MCP scope contributes no declaration of its own.
 *
 * <p>The tool list sits at the head of the prompt, so anything that moves it in the middle of a run
 * invalidates the provider's prefix cache and the signatures bound to it. Remote tools are therefore
 * reached through {@link McpToolGateway}: looking one up hands the model a schema in a message, and
 * the declaration at the head of the prompt stays byte-identical for the whole run. This class pins
 * both halves — what may appear, and that it never changes.</p>
 */
class ModelRequestFactoryVisibilityTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void noRemoteToolIsEverDeclaredHoweverManyTheScopeHolds() {
        Fixture fixture = new Fixture(List.of(staticTool("static_tool")),
                List.of(remoteTool("mcp_remote"), remoteTool("mcp_other")));

        assertEquals(Set.of(), fixture.visible(null),
                "an unnamed run declares nothing, remote or not");
        assertEquals(Set.of(), fixture.visible(List.of()),
                "an empty whitelist admits no static tool either");
        assertEquals(Set.of("static_tool"), fixture.visible(List.of("static_tool")),
                "a remote tool is never declared, not even next to its own server");
    }

    @Test
    void theGatewayAppearsOnlyWhenTheWhitelistNamesIt() {
        Fixture fixture = new Fixture(List.of(gateway(), staticTool("static_tool")), List.of());

        assertEquals(Set.of(McpToolGateway.NAME), fixture.visible(List.of(McpToolGateway.NAME)));
        assertEquals(Set.of("static_tool"), fixture.visible(List.of("static_tool")),
                "a run that never names the gateway cannot call remote tools");
        assertEquals(Set.of("static_tool"), fixture.visible(List.of(McpToolGateway.DISCOVERY_TOOL, "static_tool")),
                "the lookup entry is a tool of its own; it does not imply the gateway");
    }

    @Test
    void whitelistKeepsOnlyTheNamedTools() {
        Fixture fixture = new Fixture(
                List.of(staticTool("static_tool"), staticTool("static_other")), List.of());

        assertEquals(Set.of("static_tool"), fixture.visible(List.of("static_tool")));
    }

    /**
     * The point of the whole design: discovery writes to the conversation, never to the tool list.
     * Compared as serialized wire content, because equal lists of equal records can still differ in
     * what the provider receives.
     */
    @Test
    void theToolListStaysByteIdenticalHoweverManyRemoteToolsTheRunDiscovers() {
        List<String> whitelist = List.of(McpToolGateway.NAME, "static_a", "static_b");

        String beforeLookup = fixture(List.of(remoteTool("mcp_one"))).wire(whitelist);
        String afterLookup = fixture(List.of(
                remoteTool("mcp_one"), remoteTool("mcp_two"), remoteTool("mcp_three"))).wire(whitelist);

        assertEquals(beforeLookup, afterLookup,
                "more discovered tools must not move the head of the prompt");
        assertTrue(beforeLookup.contains(McpToolGateway.NAME),
                "the gateway is what makes the remote tools reachable at all");
    }

    /** The same run read twice returns the same bytes: the declaration carries no per-round state. */
    @Test
    void readingTheSameRunTwiceYieldsTheSameBytes() {
        Fixture fixture = fixture(List.of(remoteTool("mcp_one")));
        List<String> whitelist = List.of(McpToolGateway.NAME, "static_a");

        assertEquals(fixture.wire(whitelist), fixture.wire(whitelist));
    }

    private static Fixture fixture(List<ToolDefinition<?>> remoteTools) {
        return new Fixture(List.of(gateway(), staticTool("static_a"), staticTool("static_b")), remoteTools);
    }

    /** One static registry plus one request scope, with both layers passed in explicitly. */
    private static final class Fixture {
        private final ToolRegistry registry = new ToolRegistry(List.of());
        private final McpToolScope scope;

        private Fixture(List<ToolDefinition<?>> staticTools, List<ToolDefinition<?>> remoteTools) {
            registry.register(staticTools);
            List<ToolDefinition<? extends ToolExecutor>> scoped = new ArrayList<>(remoteTools);
            scope = scoped.isEmpty()
                    ? McpToolScope.EMPTY
                    : McpToolScope.of(List.of(sessionOf(scoped)));
        }

        private Set<String> visible(List<String> whitelist) {
            return new LinkedHashSet<>(visibleTools(whitelist).keySet());
        }

        private Map<String, ToolDefinition<?>> visibleTools(List<String> whitelist) {
            ModelChatCommand command = factory().build(execution(whitelist), whitelist, "1234567890123456789", null);
            Map<String, ToolDefinition<?>> visible = new LinkedHashMap<>();
            for (ToolDefinition<?> tool : command.chatRequest().getTools()) {
                visible.put(tool.name(), tool);
            }
            return visible;
        }

        /** The three declaration fields the provider actually receives, in list order. */
        private String wire(List<String> whitelist) {
            ArrayNode node = JSON.createArrayNode();
            for (ToolDefinition<?> tool : visibleTools(whitelist).values()) {
                ObjectNode item = node.addObject();
                item.put("name", tool.name());
                item.put("description", tool.description());
                item.put("parameters", tool.parametersJsonSchema());
            }
            try {
                return JSON.writeValueAsString(node);
            } catch (JsonProcessingException impossible) {
                throw new IllegalStateException("a JSON tree must serialize", impossible);
            }
        }

        private ModelRequestFactory factory() {
            ToolExecutionManager manager = new ToolExecutionManager() {
                public List<ToolExecuteResult> execute(ToolExecuteCommand command) { return List.of(); }

                public ToolRegistry toolRegistry() { return registry; }
            };
            return new ModelRequestFactory(RuntimeContext.builder()
                    .toolExecutionManager(manager)
                    .conversationManager(conversations())
                    .mcpToolScope(scope)
                    .build());
        }
    }

    private static McpSession sessionOf(List<ToolDefinition<? extends ToolExecutor>> scoped) {
        return new McpSession() {
            public String name() { return "test-server"; }

            public List<ToolDefinition<? extends ToolExecutor>> tools() { return scoped; }

            public void close() { }
        };
    }

    private static ConversationManager conversations() {
        return new ConversationManager() {
            public void startConversation(Execution execution, Workspace workspace, McpToolScope scope) { }

            public void addMessage(LoopMessages loopMessages) { }

            public List<Message> messages(Execution execution) { return execution.getMessages(); }

            public TokenUsageEntity tokenUsage(Execution execution) { return TokenUsageEntity.empty(); }

            public void rebuildContext(String summary, Execution execution) { }

            public void appendSystemMessage(Execution execution, SystemMessageEntity entity) { }

            public void appendMessage(Execution execution, Message message) { }
        };
    }

    private static Execution execution(List<String> whitelist) {
        return Execution.builder()
                .id("e").agentId("a")
                .messages(new ArrayList<>(List.of(UserMessageEntity.from("task"))))
                .agentRequest(AgentRequest.builder()
                        .contextSpec(ContextSpec.builder()
                                .context(List.of(UserMessageEntity.from("task")))
                                .build())
                        .toolList(whitelist)
                        .build())
                .build();
    }

    private static ToolDefinition<?> gateway() {
        return tool(McpToolGateway.NAME, "the gateway");
    }

    private static ToolDefinition<?> staticTool(String name) {
        return tool(name, "static");
    }

    private static ToolDefinition<?> remoteTool(String name) {
        return tool(name, "remote");
    }

    private static ToolDefinition<?> tool(String name, String origin) {
        return ToolDefinition.builder()
                .id(name).name(name).timeout(5L).maxOutput(100)
                .description(origin)
                .parametersJsonSchema("{\"type\":\"object\"}")
                .executor(execution -> ToolExecuteResult.success("ok"))
                .build();
    }
}
