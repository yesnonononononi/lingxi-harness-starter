package com.summit.runtime.model;

import com.summit.core.agent.Execution;
import com.summit.core.conversation.api.ChatRequestEntity;
import com.summit.core.conversation.context.RuntimeContext;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.model.ModelChatCommand;
import com.summit.core.runtime.loop.ExecutionControlSignal;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecutor;
import lombok.RequiredArgsConstructor;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Assembles the {@link ModelChatCommand} of one loop round: the conversation so far, the tools the model may see under the current whitelist, and — for streaming executions — the handler that turns model deltas into runtime events. */
@RequiredArgsConstructor
public class ModelRequestFactory {

    private final RuntimeContext context;

    /**
     * @param control cooperative control signal of the running execution, wired into the streaming
     *                handler so a suspend or cancel request interrupts the model call in flight.
     *                May be {@code null} for non-streaming or detached invocations.
     */
    public ModelChatCommand build(Execution execution, List<String> allowedTools, ExecutionControlSignal control) {
        ModelChatCommand.ModelChatCommandBuilder builder = ModelChatCommand.builder()
                .chatRequest(ChatRequestEntity.builder()
                        .messages(context.getConversationManager().messages(execution))
                        .tools(availableTools( allowedTools))
                        .build())
                .thinking(execution.isThinking())
                .streaming(execution.isStreaming());
        if (execution.isStreaming()) {
            builder.streamingChatResponseHandler(streamingHandler(execution, control));
        }
        return builder.build();
    }

    /** Publishes partial text / thinking as runtime events and completes the future on the final response. */
    private StreamingModelResponseBehaveDecider streamingHandler(Execution execution, ExecutionControlSignal control) {
        return new StreamingModelResponseBehaveDecider(context.getRuntimeEventPublisher(),
                StreamingModelResponseBehaveDecider.StreamingResponseContext.builder()
                        .executionId(execution.getId())
                        .agentId(execution.getAgentId())
                        .future(new CompletableFuture<>())
                        .build(),
                control);
    }

    /**
     * Tools exposed to the model this round: the static registry filtered by the whitelist, plus the
     * request's own MCP tools that have been disclosed so far.
     *
     * <p>The whitelist admits nothing when {@code null} or empty. A request's MCP tools are the one
     * thing an absent list cannot name — their names did not exist when any list could have been
     * written — but that is a reason to disclose them <em>progressively</em>, not to declare them all
     * at once: they enter only after {@code search_tool} has handed the model their definitions and
     * recorded them on the {@link McpToolScope}. Before that the model knows they exist (the prompt
     * carries their résumés) but cannot call them, which is the point of a lookup entry.</p>
     *
     * <p>Admission in {@code DefaultToolExecutionManager} is deliberately looser and admits any tool
     * of the scope. The two gates are not meant to agree: this one decides what the model is offered,
     * that one decides what a call is allowed to reach. A model that names a disclosed tool in the
     * same batch as the search that revealed it is therefore served, not rejected.</p>
     *
     * <p>Static tools come first so an MCP tool can never shadow a framework built-in.</p>
     */
    private List<ToolDefinition<?>> availableTools(List<String> allowedTools) {
        McpToolScope mcpScope = context.getMcpToolScope();
        Map<String, ToolDefinition<?>> candidates = new LinkedHashMap<>();
        Collection<ToolDefinition<? extends ToolExecutor>> registered = context.getToolExecutionManager()
                .toolRegistry().getTools().values();
        registered.forEach(tool -> candidates.put(tool.name(), tool));
        // Only the disclosed slice of the request's MCP tools: everything else stays a résumé in the
        // prompt until the model asks for it.
        mcpScope.disclosedTools().forEach(tool -> candidates.putIfAbsent(tool.name(), tool));

        return candidates.values().stream()
                .<ToolDefinition<?>>map(tool -> tool)
                .filter(tool -> tool.allowedFor(allowedTools)
                        || mcpScope.isDisclosed(tool.name()))
                .toList();
    }
}
