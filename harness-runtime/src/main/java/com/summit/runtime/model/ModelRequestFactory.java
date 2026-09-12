package com.summit.runtime.model;

import com.summit.core.agent.Execution;
import com.summit.core.conversation.api.ChatRequestEntity;
import com.summit.core.conversation.context.RuntimeContext;
import com.summit.core.model.ModelChatCommand;
import com.summit.core.tool.LoopBoundary;
import com.summit.core.tool.ToolDefinition;
import com.summit.runtime.agent.AgentLoopRunner;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Assembles the {@link ModelChatCommand} of one loop round: the conversation so far, the tools the
 * model may see under the current boundary, and — for streaming executions — the handler that turns
 * model deltas into runtime events.
 *
 * <p>Split out of {@link AgentLoopRunner} so the loop only decides <em>when</em> to ask the model,
 * never <em>what</em> the request contains.</p>
 */
@RequiredArgsConstructor
public class ModelRequestFactory {

    private final RuntimeContext context;

    public ModelChatCommand build(Execution execution, Serializable sessionId, LoopBoundary boundary) {
        ModelChatCommand.ModelChatCommandBuilder builder = ModelChatCommand.builder()
                .chatRequest(ChatRequestEntity.builder()
                        .messages(context.getConversationManager().messages(sessionId))
                        .tools(availableTools(boundary))
                        .build())
                .thinking(execution.isThinking())
                .streaming(execution.isStreaming());
        if (execution.isStreaming()) {
            builder.streamingChatResponseHandler(streamingHandler(execution, sessionId));
        }
        return builder.build();
    }

    /** Publishes partial text / thinking as runtime events and completes the future on the final response. */
    private StreamingModelResponseBehaveDecider streamingHandler(Execution execution, Serializable sessionId) {
        return new StreamingModelResponseBehaveDecider(context.getRuntimeEventPublisher(),
                StreamingModelResponseBehaveDecider.StreamingResponseContext.builder()
                        .sessionId(sessionId)
                        .executionId(execution.getId())
                        .agentId(execution.getAgentId())
                        .future(new CompletableFuture<>())
                        .build());
    }

    /**
     * Tools exposed to the model for the current boundary:
     * <ul>
     *   <li>planning-only tools are hidden once the boundary allows execution, so a planning tool
     *       (e.g. {@code create_plan}) can never interrupt an implementation run;</li>
     *   <li>under the PLANING boundary only read-only tools are passed, so the model cannot issue
     *       write calls while planning; otherwise (EXECUTE or absent) the whole remaining set is
     *       passed. The {@code ToolExecutionContext#allowToolExecution} interceptor remains as a
     *       backstop.</li>
     * </ul>
     */
    private List<ToolDefinition<?>> availableTools(LoopBoundary boundary) {
        boolean allowsExecute = LoopBoundary.allowExecute(boundary);
        List<ToolDefinition<?>> tools = context.getToolExecutionManager().toolRegistry().getTools().values().stream()
                .<ToolDefinition<?>>map(tool -> tool)
                .filter(tool -> !(tool.planningOnly() && allowsExecute))
                .toList();
        if (allowsExecute) {
            return tools;
        }
        return tools.stream()
                .filter(ToolDefinition::readOnly)
                .toList();
    }
}
