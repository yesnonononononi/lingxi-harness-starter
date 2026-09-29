package com.summit.runtime.loop;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.context.RuntimeContext;
import com.summit.core.conversation.event.AgentMessageEvent;
import com.summit.core.conversation.event.ContextUpdateEvent;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.model.ModelChatCommand;
import com.summit.core.runtime.loop.*;
import com.summit.core.runtime.loop.suspension.ExecutionInterruptedException;
import com.summit.core.tool.ToolExecuteCommand;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolResultType;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.model.ModelRequestFactory;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Runs complete model/tool rounds until the model finishes or a cooperative control boundary stops
 * the execution. A suspended execution deliberately discards an uncommitted round and starts with a
 * fresh model request when it is resumed.
 */
@Slf4j
public class AgentLoopStepRunner {

    private final RuntimeContext context;
    private final ModelRequestFactory requests;

    public AgentLoopStepRunner(RuntimeContext context) {
        this.context = context;
        this.requests = new ModelRequestFactory(context);
    }

    public LoopResult run(Execution execution, ExecutionControlSignal control) throws Exception {
        int consecutiveCompactRounds = 0;
        CheckPointResult checkPointResult;
        LoopResult controlResult;

        LoopInterceptor interceptor = this.context.getLoopInterceptor();

        LoopContext loopContext = new LoopContext(
                execution.getId(),
                execution.getAgentRequest() == null ? null
                        : execution.getAgentRequest().runtimeParametersOrDefault().getAttributes(),
                e -> e.forEach(m -> this.context.getConversationManager().appendMessage(execution, m))
        );
        while (true) {
            if ((controlResult = resultOf(control)) != null) return controlResult;
            try {
                interceptor.onLoopStart(loopContext);
                interceptor.onBeforeModelInvoke(loopContext);

                if ((controlResult = resultOf(control)) != null) return controlResult;

                // Include externally appended messages in budget/compaction checks.
                if ((checkPointResult = boundaryCheck(true, execution)) != null)
                    return LoopResult.cancelled(checkPointResult.reason());

                ChatResponseEntity response;
                try {
                    execution.setModelAttempts(execution.getModelAttempts() + 1);

                    response = invokeModel(execution, control);
                } catch (ExecutionInterruptedException e) {
                    return e.kind() == ExecutionInterruptedException.Kind.CANCEL
                            ? LoopResult.cancelled(e.getMessage())
                            : LoopResult.suspended(e.getMessage());
                }
                interceptor.onAfterModelInvoke(loopContext, response);

                if ((controlResult = resultOf(control)) != null) return controlResult;

                AiMessageEntity aiMessage = response.getAiMessageEntity();
                if (hasNoToolCall(aiMessage)) {
                    appendMessage(execution, response, null);
                    return LoopResult.completed();
                }

                if ((controlResult = resultOf(control)) != null) return controlResult;
                interceptor.onBeforeToolCall(loopContext);
                if ((controlResult = resultOf(control)) != null) return controlResult;

                List<ToolExecuteResult> toolResults = doToolCall(execution, aiMessage);

                if (hasPromise(toolResults)) {
                    appendMessage(execution, response, toolResults);
                    interceptor.onAfterToolCall(loopContext, toolResults);
                    reportCompletedRound(execution);
                    if ((controlResult = resultOf(control)) != null
                            && controlResult.status() == LoopResult.Status.CANCELLED) return controlResult;
                    return LoopResult.suspended("tool result requested execution suspension");
                }

                if (applyCompaction(execution, response, toolResults)) {
                    interceptor.onAfterToolCall(loopContext, toolResults);

                    reportCompletedRound(execution);

                    consecutiveCompactRounds++;

                    if (consecutiveCompactRounds >= maxConsecutiveCompactions()) {
                        return LoopResult.cancelled("too many consecutive context compactions");
                    }

                    continue;
                }

                consecutiveCompactRounds = 0;
                appendMessage(execution, response, toolResults);
                interceptor.onAfterToolCall(loopContext, toolResults);

                if ((checkPointResult = boundaryCheck(false, execution)) != null)
                    return LoopResult.cancelled(checkPointResult.reason());

                reportCompletedRound(execution);

            } finally {
                // A round end notification must not mask the loop result or original failure.
                try {
                    interceptor.onLoopEnd(loopContext);
                } catch (Exception e) {
                    log.warn("Loop end callback failed: executionId={}", execution.getId(), e);
                }
            }
        }
    }

    private @NonNull ChatResponseEntity invokeModel(Execution execution, ExecutionControlSignal control) throws Exception {
        try {
            ChatResponseEntity response = context.getInvoker().invoke(requestOf(execution, control));
            context.getRuntimeEventPublisher().onAiMessage(new AgentMessageEvent(
                    response.getAiMessageEntity().text(),
                    response.getAiMessageEntity().getThinking(),
                    execution.getId()));
            return response;
        } catch (Exception e) {
            // A control request reached the streaming handler while the model was still producing
            // output. The streaming invoker waits on a CompletableFuture, and CompletableFuture.join()
            // wraps whatever completed the future in a CompletionException. Unwrap it so the control
            // flow signal is recognized instead of being reported as an execution failure.
            ExecutionInterruptedException interrupted = findInterrupted(e);
            if (interrupted != null) {
                throw interrupted;
            }
            throw e;
        }
    }

    /**
     * Walks the cause chain looking for the control-flow signal raised by the streaming handler,
     * hiding the wrapper types added by {@link java.util.concurrent.CompletableFuture}. The walk
     * stops as soon as an unrelated exception shows up, so a genuine model failure keeps its own
     * identity and is still reported as a failure.
     */
    private @Nullable ExecutionInterruptedException findInterrupted(Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof ExecutionInterruptedException interrupted) return interrupted;
            boolean wrapper = cause instanceof CompletionException
                    || cause instanceof ExecutionException
                    || cause.getClass() == RuntimeException.class;
            if (!wrapper) return null;
            Throwable next = cause.getCause();
            if (next == cause) return null;
            cause = next;
        }
        return null;
    }

    private @Nullable LoopResult resultOf(@NonNull ExecutionControlSignal control) {
        if (control.isCancelRequired()) {
            return LoopResult.cancelled("execution cancellation requested");
        }
        if (control.isSuspendRequired()) {
            return LoopResult.suspended("execution suspension requested");
        }
        return null;
    }

    private List<ToolExecuteResult> doToolCall(@NonNull Execution execution,
                                               @NonNull AiMessageEntity aiMessageEntity) {
        AgentRequest agentRequest = execution.getAgentRequest();
        return context.getToolExecutionManager().execute(
                new ToolExecuteCommand(
                        aiMessageEntity.getToolCalls(),
                        execution.getId(),
                        context.getWorkspace(),
                        agentRequest == null ? null : agentRequest.runtimeParametersOrDefault().getAttributes(),
                        configuredTools(execution),
                        agentRequest == null || agentRequest.runtimeParametersOrDefault().isAllowOutsideWorkspace(),
                        context.getMcpToolScope())
        );
    }

    private void appendMessage(Execution execution, ChatResponseEntity response, List<ToolExecuteResult> toolResults) {
        context.getConversationManager().addMessage(execution, response, toolResults);
    }

    private ModelChatCommand requestOf(Execution execution, ExecutionControlSignal control) {
        return requests.build(execution, configuredTools(execution), control);
    }

    private @Nullable List<String> configuredTools(@NonNull Execution execution) {
        AgentRequest request = execution.getAgentRequest();
        return request == null ? null : request.getToolList();
    }

    /**
     * Mixed batches keep all results; only a dedicated successful compact round replaces context.
     * The rebuilt context is what must survive the round: the compacted view is kept as the live
     * context while the compact round itself is appended only to the transcript, so the model is
     * served the summary instead of the history it replaces.
     */
    private boolean applyCompaction(Execution execution, ChatResponseEntity response, List<ToolExecuteResult> results) {
        ToolExecuteResult result = results.getFirst();

        if (response.getAiMessageEntity().getToolCalls().size() != 1 || results.size() != 1)
            return false;

        if (!result.isSuccess() || result.getToolResultType() != ToolResultType.CONTEXT_COMPACT)
            return false;

        if (!context.getConversationManager().applyCompactSummary(result.getToolOutput(), execution, true))
            return false;

        List<Message> compacted = execution.getMessages();
        execution.setMessages(new ArrayList<>(compacted));
        try {
            // Record the compact round in the transcript and accounting only: it must not stay in
            // the model context, so the rebuilt view captured above is restored afterwards.
            appendMessage(execution, response, results);
        } finally {
            execution.setMessages(compacted);
        }

        if (context.getUsage() != null) context.getUsage().publish(execution,
                ContextUpdateEvent.Phase.SQUEEZE_COMPLETED, "Tool compaction completed");

        return true;
    }

    private boolean hasPromise(List<ToolExecuteResult> toolResults) {
        return toolResults != null && toolResults.stream()
                .anyMatch(result -> result != null && result.isPromise());
    }

    /**
     * Consecutive compaction rounds this run tolerates: the configured budget when the context
     * carries one, the runtime default otherwise. A context built outside Spring has no opinion, and
     * the loop must still bound the run rather than compact forever.
     */
    private int maxConsecutiveCompactions() {
        Integer configured = context.getMaxConsecutiveCompactions();
        return configured == null ? AgentConfig.DEFAULT_MAX_CONSECUTIVE_COMPACTIONS : configured;
    }

    private void reportCompletedRound(Execution execution) {
        if (context.getUsage() != null) context.getUsage().afterRound(execution);
        if (context.getExecutionRepository() != null) context.getExecutionRepository().save(execution);
    }

    private boolean hasNoToolCall(@NonNull AiMessageEntity response) {
        List<ToolCallRequest> toolCalls = response.getToolCalls();
        return toolCalls == null || toolCalls.isEmpty();
    }

    private @Nullable CheckPointResult boundaryCheck(boolean before, Execution execution) {
        CheckPointResult result = before ? context.getRuntimeBoundaryChecker().before(execution) : context.getRuntimeBoundaryChecker().after(execution);
        return result.isCancelled() ? result : null;
    }


}
