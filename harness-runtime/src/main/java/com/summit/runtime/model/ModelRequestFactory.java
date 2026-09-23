package com.summit.runtime.model;

import com.summit.core.agent.Execution;
import com.summit.core.conversation.api.ChatRequestEntity;
import com.summit.core.conversation.context.RuntimeContext;
import com.summit.core.model.ModelChatCommand;
import com.summit.core.runtime.loop.ExecutionControlSignal;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecutor;
import lombok.RequiredArgsConstructor;
import java.util.Collection;
import java.util.List;
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

    /** Tools exposed to the model for the current round: every registered tool the run's whitelist admits. The whitelist is the single source of truth — the same list gates execution in {@code DefaultToolExecutionManager}, so hiding a tool here is enough to keep it unavailable. */
    private List<ToolDefinition<?>> availableTools(List<String> allowedTools) {
        Collection<ToolDefinition<? extends ToolExecutor>> registered = context.getToolExecutionManager()
                .toolRegistry().getTools().values();
        return registered.stream()
                .<ToolDefinition<?>>map(tool -> tool)
                .filter(tool -> tool.allowedFor(allowedTools))
                .toList();
    }
}
