package com.summit.runtime.model;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.conversation.ConversationManager;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.runtime.context.RuntimeContext;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.conversation.message.TokenUsageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.mcp.McpSession;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.model.ModelChatCommand;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.tool.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Model-visible tools = whitelisted static tools ∪ the request's <em>disclosed</em> MCP tools.
 *
 * <p>The whitelist is an allow-list with no implicit default: {@code null} and empty both admit no
 * static tool. Remote tools take the other road — they are never declared just for existing. A
 * request's MCP tools stay out of the tool list until the search entry has handed the model their
 * definitions and recorded them on the scope, so "discovered" and "offered to the model" are two
 * different states, and this class pins the difference.</p>
 */
class ModelRequestFactoryVisibilityTest {

    @Test
    void undiscoveredRemoteToolsAreNeverDeclared() {
        Fixture fixture = new Fixture(List.of(staticTool("static_tool")), List.of(remoteTool("mcp_remote")));

        assertEquals(Set.of(), fixture.visible(null),
                "a remote tool nobody asked for must not be declared");
        assertEquals(Set.of(), fixture.visible(List.of()),
                "an empty whitelist admits no static tool either");
    }

    @Test
    void disclosedRemoteToolIsDeclaredEvenThoughNoListCouldHaveNamedIt() {
        Fixture fixture = new Fixture(List.of(staticTool("static_tool")), List.of(remoteTool("mcp_remote")));

        fixture.scope.disclose(List.of("mcp_remote"));

        assertEquals(Set.of("mcp_remote"), fixture.visible(null));
        assertEquals(Set.of("mcp_remote"), fixture.visible(List.of()),
                "disclosure is independent of the static whitelist");
    }

    @Test
    void whitelistKeepsOnlyTheNamedTools() {
        Fixture fixture = new Fixture(
                List.of(staticTool("static_tool"), staticTool("static_other")), List.of());

        assertEquals(Set.of("static_tool"), fixture.visible(List.of("static_tool")));
    }

    @Test
    void disclosureOfANameTheScopeDoesNotHoldIsIgnored() {
        Fixture fixture = new Fixture(List.of(staticTool("static_tool")), List.of(remoteTool("mcp_remote")));

        // The search entry matches over the registry and the scope together and does not sort the
        // hits by origin, so static names land here too.
        fixture.scope.disclose(List.of("static_tool", "mcp_absent"));

        assertEquals(Set.of(), fixture.visible(null),
                "neither a static name nor an unknown one may widen the remote slice");
    }

    @Test
    void aRunWithNoMcpServerOfItsOwnExposesNothingWhenUnnamed() {
        Fixture fixture = new Fixture(List.of(staticTool("static_tool")), List.of());

        assertEquals(Set.of(), fixture.visible(null));
        assertEquals(Set.of(), fixture.visible(List.of()));
    }

    /** A request-level tool can never shadow a framework built-in of the same name. */
    @Test
    void staticToolWinsOnNameCollision() {
        Fixture fixture = new Fixture(List.of(tool("shared", "static")), List.of(tool("shared", "remote")));
        fixture.scope.disclose(List.of("shared"));

        Map<String, ToolDefinition<?>> tools = fixture.visibleTools(List.of("shared"));

        assertEquals(1, tools.size());
        assertEquals("static", tools.get("shared").description(),
                "the registry layer is put in first, so the remote one must not displace it");
    }

    /** One static registry plus one request scope, with both layers passed in explicitly. */
    private static final class Fixture {
        private final ToolRegistry registry = new ToolRegistry(List.of());
        private final McpToolScope scope;

        private Fixture(List<ToolDefinition<?>> staticTools, List<ToolDefinition<?>> remoteTools) {
            staticTools.forEach(tool -> registry.getTools().put(tool.name(), tool));
            List<ToolDefinition<? extends ToolExecutor>> scoped = new ArrayList<>(remoteTools);
            scope = scoped.isEmpty()
                    ? McpToolScope.EMPTY
                    : McpToolScope.of(List.of(sessionOf(scoped)));
        }

        private Set<String> visible(List<String> whitelist) {
            return new java.util.LinkedHashSet<>(visibleTools(whitelist).keySet());
        }

        private Map<String, ToolDefinition<?>> visibleTools(List<String> whitelist) {
            ModelChatCommand command = factory().build(execution(whitelist), whitelist, null);
            Map<String, ToolDefinition<?>> visible = new java.util.LinkedHashMap<>();
            for (ToolDefinition<?> tool : command.chatRequest().getTools()) {
                visible.put(tool.name(), tool);
            }
            return visible;
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

            public void addMessage(Execution execution, ChatResponseEntity response,
                                   List<ToolExecuteResult> results) { }

            public List<Message> messages(Execution execution) { return execution.getMessages(); }

            public TokenUsageEntity tokenUsage(Execution execution) { return TokenUsageEntity.empty(); }

            public void rebuildContext(String summary, Execution execution,
                                       boolean answeredTrailingUserTurn) { }

            public void appendSystemMessage(Execution execution, SystemMessageEntity entity) { }

            public void appendMessage(Execution execution, Message message) { }
        };
    }

    private static Execution execution(List<String> whitelist) {
        return Execution.builder()
                .id("e").agentId("a")
                .messages(new ArrayList<>(List.of(UserMessageEntity.from("task"))))
                .agentRequest(AgentRequest.builder()
                        .messages(List.of(UserMessageEntity.from("task")))
                        .toolList(whitelist)
                        .build())
                .build();
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
                .executor(execution -> ToolExecuteResult.success("ok"))
                .build();
    }
}
