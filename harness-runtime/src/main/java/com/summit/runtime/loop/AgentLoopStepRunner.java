package com.summit.runtime.loop;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.context.RuntimeContext;
import com.summit.core.conversation.event.AgentMessageEvent;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.model.ModelChatCommand;
import com.summit.core.runtime.loop.CheckPointResult;
import com.summit.core.runtime.loop.LoopResult;
import com.summit.core.runtime.loop.ExecutionControlSignal;
import com.summit.core.runtime.loop.suspension.ExecutionInterruptedException;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecuteCommand;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.runtime.compact.ContextCompactReconciler;
import com.summit.runtime.model.ModelRequestFactory;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

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

    private static final int MAX_CONSECUTIVE_COMPACTIONS = 3;

    private final RuntimeContext context;
    private final ModelRequestFactory requests;
    private final ContextCompactReconciler compaction;

    public AgentLoopStepRunner(RuntimeContext context) {
        this.context = context;
        this.requests = new ModelRequestFactory(context);
        this.compaction = new ContextCompactReconciler(context.getConversationManager());
    }

    public LoopResult run(Execution execution, ExecutionControlSignal control) throws Exception {
        boolean writeToolExecuted = false;
        int consecutiveCompactRounds = 0;
        CheckPointResult checkPointResult;
        LoopResult controlResult;
        while (true) {
            if ((controlResult = resultOf(control, writeToolExecuted)) != null) return controlResult;

            if ((checkPointResult = checkPoint(true, execution)) != null) return LoopResult.cancelled(checkPointResult.reason(), writeToolExecuted);


            ChatResponseEntity response;
            try {
                response = invokeModel(execution, control);
            } catch (ExecutionInterruptedException e) {
                // The round never produced a committed reply, so it is discarded — consistent with
                // the suspended-execution contract documented on this class.
                return e.kind() == ExecutionInterruptedException.Kind.CANCEL
                        ? LoopResult.cancelled(e.getMessage(), writeToolExecuted)
                        : LoopResult.suspended(e.getMessage(), writeToolExecuted);
            }
            AiMessageEntity aiMessage = response.getAiMessageEntity();

            if (hasNoToolCall(aiMessage)) {
                appendMessage(execution, response, null);
                return LoopResult.completed(writeToolExecuted);
            }

            if ((controlResult = resultOf(control, writeToolExecuted)) != null) return controlResult;

            List<ToolExecuteResult> toolResults = doToolCall(execution, aiMessage);
            writeToolExecuted = hasExecutedWriteTool(aiMessage, toolResults);

            if (hasPromise(toolResults)) {
                appendMessage(execution, response, toolResults);
                reportCompletedRound(execution);

                if ((controlResult = resultOf(control, writeToolExecuted)) != null && controlResult.status() == LoopResult.Status.CANCELLED) {
                    return controlResult;
                }
                return LoopResult.suspended("tool result requested execution suspension", writeToolExecuted);
            }

            if (compaction.reconcile(toolResults, execution)) {
                consecutiveCompactRounds++;
                if (consecutiveCompactRounds >= MAX_CONSECUTIVE_COMPACTIONS) {
                    final String REASON = "too many consecutive context compactions";
                    log.warn("【agent-loop】process is stopped: executionId={}, reason={}", execution.getId(), REASON);
                    return LoopResult.cancelled(REASON, writeToolExecuted);
                }
                continue;
            }

            consecutiveCompactRounds = 0;
            appendMessage(execution, response, toolResults);


            if ((checkPointResult = checkPoint(false, execution)) != null) return LoopResult.cancelled(checkPointResult.reason(), writeToolExecuted);

            reportCompletedRound(execution);
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
            // flow signal is recognised instead of being reported as an execution failure.
            ExecutionInterruptedException interrupted = findInterrupted(e);
            if (interrupted != null) {
                log.info("【agent-loop】model call interrupted, executionId={}, kind={}",
                        execution.getId(), interrupted.kind());
                throw interrupted;
            }
            log.error("【agent-loop】process error in model invoke: {}", execution.getId(), e);
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

    private @Nullable LoopResult resultOf(@NonNull ExecutionControlSignal control, boolean writeToolExecuted) {
        if (control.isCancelRequired()) {
            return LoopResult.cancelled("execution cancellation requested", writeToolExecuted);
        }
        if (control.isSuspendRequired()) {
            return LoopResult.suspended("execution suspension requested", writeToolExecuted);
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
                        agentRequest == null || agentRequest.runtimeParametersOrDefault().isAllowOutsideWorkspace())
        );
    }

    private void appendMessage(Execution execution, ChatResponseEntity response, List<ToolExecuteResult> toolResults){
        context.getConversationManager().addMessage(execution, response, toolResults);
    }

    private ModelChatCommand requestOf(Execution execution, ExecutionControlSignal control) {
        return requests.build(execution, configuredTools(execution), control);
    }

    private @Nullable List<String> configuredTools(@NonNull Execution execution) {
        AgentRequest request = execution.getAgentRequest();
        return request == null ? null : request.getToolList();
    }

    private boolean hasExecutedWriteTool(@NonNull AiMessageEntity aiMessage, List<ToolExecuteResult> toolResults) {
        List<ToolCallRequest> calls = aiMessage.getToolCalls();
        if (calls == null || calls.isEmpty()) return false;

        int paired = Math.min(calls.size(), toolResults.size());
        for (int i = 0; i < paired; i++) {
            ToolExecuteResult result = toolResults.get(i);
            if (result == null || !result.isSuccess() || result.isPromise()) continue;

            ToolDefinition<?> definition = context.getToolExecutionManager()
                    .toolRegistry().getTool(calls.get(i).name());
            if (definition != null && !definition.readOnly()) return true;
        }
        return false;
    }

    private boolean hasPromise(List<ToolExecuteResult> toolResults) {
        return toolResults != null && toolResults.stream()
                .anyMatch(result -> result != null && result.isPromise());
    }

    private void reportCompletedRound(Execution execution) {
        if (context.getUsage() != null) context.getUsage().afterRound(execution);
        if (context.getExecutionRepository() != null) context.getExecutionRepository().save(execution);
    }

    private boolean hasNoToolCall(@NonNull AiMessageEntity response) {
        List<ToolCallRequest> toolCalls = response.getToolCalls();
        return toolCalls == null || toolCalls.isEmpty();
    }

    private @Nullable CheckPointResult checkPoint(boolean before, Execution execution){
        CheckPointResult result = before ? context.getRuntimeBoundaryChecker().before(execution) :context.getRuntimeBoundaryChecker().after(execution);
        if (result.isCancelled()) {
            log.warn("【agent-loop】process stopped at runtime boundary: executionId={}, reason={}",
                    execution.getId(), result.reason());
            return result;
        }
        return null;
    }


}
