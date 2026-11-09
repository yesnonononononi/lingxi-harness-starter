package com.summit.runtime.model;

import com.summit.core.agent.Execution;
import com.summit.core.conf.ModelRetryConfig;
import com.summit.core.conversation.api.ChatRequestEntity;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.event.InvokeRetryEvent;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.model.ModelChatCommand;
import com.summit.core.model.ModelInvocationUsage;
import com.summit.core.runtime.loop.ExecutionControlSignal;
import com.summit.core.utils.RetryUtil;
import com.summit.core.tool.ToolDefinition;
import com.summit.runtime.context.RuntimeContext;
import com.summit.runtime.tool.ModelToolVisibility;
import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Assembles the {@link ModelChatCommand} of one loop round: the conversation so far, the tools the model may see under the current whitelist, and — for streaming executions — the handler that turns model deltas into runtime events.
 */
@RequiredArgsConstructor
public class ModelRequestFactory {

    private final RuntimeContext context;

    /**
     * @param control cooperative control signal of the running execution, wired into the streaming
     *                handler so a suspend or cancel request interrupts the model call in flight.
     *                May be {@code null} for non-streaming or detached invocations.
     */
    public ModelChatCommand build(Execution execution,
                                  List<String> allowedTools,
                                  String responseId,
                                  ExecutionControlSignal control
    ) {
        return createCommand(execution, responseId, control, snapshotRequest(execution, allowedTools),
                execution.getAgentRequest().getRetryConfig(), null);
    }

    /**
     * Retries share this round's input collections; only attempt identity and streaming state change.
     */
    public Supplier<ModelChatCommand> buildAttempts(Execution execution, List<String> allowedTools,
                                                    ExecutionControlSignal control, ModelInvocationUsage invocationUsage) {
        Objects.requireNonNull(invocationUsage, "invocationUsage");
        ChatRequestEntity snapshot = snapshotRequest(execution, allowedTools);
        ModelRetryConfig retryConfig = execution.getAgentRequest().getRetryConfig();
        return () -> createCommand(execution, execution.nextResponseId(context.getResponseIdGenerator()),
                control, snapshot, retryConfig, invocationUsage);
    }

    private ChatRequestEntity snapshotRequest(Execution execution, List<String> allowedTools) {
        return ChatRequestEntity.builder()
                .imageGenerationOptions(execution.getAgentRequest().getImageGenerationOptions())
                .messages(List.copyOf(context.getConversationManager().messages(execution)))
                .tools(availableTools(allowedTools)).build();
    }

    private ModelChatCommand createCommand(Execution execution, String responseId, ExecutionControlSignal control,
                                           ChatRequestEntity snapshot, ModelRetryConfig retryConfig,
                                           ModelInvocationUsage invocationUsage) {
        ModelChatCommand.ModelChatCommandBuilder builder = ModelChatCommand.builder()
                .executionId(execution.getId())
                .responseId(responseId)
                .signal(control)
                .eventMetaData(execution.eventMetaData())
                .beforeAttempt(() -> {
                    context.getRuntimeBoundaryChecker().checkModelAttempt(execution);
                    if (control != null) control.checkInterruption();
                    execution.incrementModelAttempts();
                })
                .chatRequest(ChatRequestEntity.builder()
                        .imageGenerationOptions(snapshot.getImageGenerationOptions())
                        .messages(snapshot.getMessages())
                        .tools(snapshot.getTools())
                        .build())
                .retryConfig(retryConfig)
                .invocationUsage(invocationUsage)
                .thinking(execution.isThinking())
                .runtimeEventPublisher(context.getRuntimeEventPublisher())
                .streaming(execution.isStreaming());
        if (execution.isStreaming()) {
            builder.streamingChatResponseHandler(streamingHandler(execution, control, responseId, invocationUsage));
        }
        return builder.build();
    }

    /**
     * The invoker supplies the task; request configuration can only select retry settings.
     *
     * @param command        the attempt whose settings and events are bound to the policy
     * @param task           supplier of the task to run; consulted once per attempt so a retry can
     *                       run in its own transaction boundary or with a brand new command object
     * @param currentAttempt supplier of the attempt currently in flight, used for retry events
     */
    public static RetryUtil.RetryConf<ChatResponseEntity> buildRetryConfig(ModelChatCommand command,
                                                                           Supplier<RetryUtil.Task<ChatResponseEntity>> task,
                                                                           Supplier<ModelChatCommand> currentAttempt
    ) {
        ModelRetryConfig settings = Objects.requireNonNull(command.retryConfig(), "retryConfig");
        Duration initialDelay = Objects.requireNonNull(settings.getInitialDelay(), "initialDelay");
        Duration maxDelay = Objects.requireNonNull(settings.getMaxDelay(), "maxDelay");
        if (initialDelay.isNegative() || maxDelay.isNegative() || settings.getMaxRetries() < 0 || settings.getMultiplier() < 1) {
            throw new IllegalArgumentException("Retry delays and count must be non-negative; multiplier must be positive");
        }
        return new RetryUtil.RetryConf<>(initialDelay.toMillis(), settings.getMaxRetries(), settings.getMultiplier(), maxDelay.toMillis(),
                TimeUnit.MILLISECONDS, task,
                context -> {
                    if (command.signal() != null) command.signal().checkInterruption();
                    return false;
                },
                retryContext -> {
                    if (command.runtimeEventPublisher() != null) {
                        ModelChatCommand failed = currentAttempt.get();
                        command.runtimeEventPublisher().onInvokeRetry(InvokeRetryEvent.builder()
                                .executionId(command.executionId()).responseId(failed.responseId())
                                .metaData(command.eventMetaData()).retryCount(retryContext.retryCount())
                                .retryDelay(retryContext.delay()).timeUnit(retryContext.timeUnit()).build());
                    }
                },
                ModelRetryPolicy::shouldRetry);
    }

    /**
     * Publishes partial text / thinking as runtime events and completes the future on the final response.
     */
    private StreamingModelResponseBehaveDecider streamingHandler(Execution execution, ExecutionControlSignal control,
                                                                String responseId, ModelInvocationUsage invocationUsage) {
        return new StreamingModelResponseBehaveDecider(context.getRuntimeEventPublisher(),
                StreamingModelResponseBehaveDecider.StreamingResponseContext.builder()
                        .executionId(execution.getId())
                        .agentId(execution.getAgentId())
                        .eventMetaData(execution.eventMetaData())
                        .responseId(responseId)
                        .invocationUsage(invocationUsage)
                        .future(new CompletableFuture<>())
                        .build(),
                control);
    }

    private List<ToolDefinition<?>> availableTools(List<String> allowedTools) {
        return ModelToolVisibility.select(context.getToolExecutionManager().toolRegistry().getTools().values(),
                context.getMcpToolScope().disclosedTools(), allowedTools).all();
    }
}
