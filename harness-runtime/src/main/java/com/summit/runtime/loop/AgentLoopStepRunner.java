package com.summit.runtime.loop;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.AgentRuntimeParameters;
import com.summit.core.agent.Execution;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.api.ToolCallRequest;
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
import com.summit.runtime.compact.CompactSummaryApplier;
import com.summit.runtime.context.RuntimeContext;
import com.summit.runtime.model.ModelRequestFactory;
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
 * <p>
 * The runner itself is stateless. Everything that belongs to a single {@link #run} call lives in
 * the private {@link Run} object, so one runner instance can serve many executions.
 */
public class AgentLoopStepRunner {

    private final RuntimeContext context;
    private final ModelRequestFactory modelRequests;
    private final LoopBoundaryGuard boundaryGuard;

    public AgentLoopStepRunner(RuntimeContext context) {
        this.context = context;
        this.modelRequests = new ModelRequestFactory(context);
        this.boundaryGuard = new LoopBoundaryGuard(context.getRuntimeBoundaryChecker());
    }

    public LoopResult run(Execution execution, ExecutionControlSignal control) throws Exception {
        return new Run(execution, control).execute();
    }

    // ---------------------------------------------------------------------------------------
    // Round outcome
    // ---------------------------------------------------------------------------------------

    /**
     * What a single round tells the loop: stop with a result, or go around again. A round that
     * went around again after a compaction is distinguished so the loop can bound the streak.
     */
    private record RoundOutcome(@Nullable LoopResult result, boolean compacted) {

        static final RoundOutcome NEXT = new RoundOutcome(null, false);
        static final RoundOutcome COMPACTED = new RoundOutcome(null, true);

        static RoundOutcome finish(LoopResult result) {
            return new RoundOutcome(result, false);
        }

        boolean isFinal() {
            return result != null;
        }
    }

    // ---------------------------------------------------------------------------------------
    // One execution's worth of loop state
    // ---------------------------------------------------------------------------------------

    private final class Run {

        private final Execution execution;
        private final ExecutionControlSignal control;
        private final LoopMessages messages;
        private final LoopInterceptorProcessor interceptor;
        private final LoopContext loopContext;
        private int consecutiveCompactRounds;

        private Run(Execution execution, ExecutionControlSignal control) {
            this.execution = execution;
            this.control = control;
            this.messages = LoopMessages.builder().execution(execution).build();
            this.interceptor = context.getLoopInterceptorProcessor();
            this.loopContext = LoopContext.withCompactionCounter(
                    messages,
                    control,
                    () -> consecutiveCompactRounds,
                    appended -> appended.forEach(m -> context.getConversationManager().appendMessage(execution, m))
            );
        }

        LoopResult execute() throws Exception {
            while (true) {
                RoundOutcome outcome;
                try (LoopRoundScope ignored = new LoopRoundScope(interceptor, loopContext)) {
                    outcome = runRound();
                } finally {
                    // onLoopEnd observes the current round; every exit, including compaction's
                    // continue, clears it before the next round can reuse this context.
                    messages.clear();
                }

                if (outcome.isFinal()) return outcome.result();

                if (outcome.compacted() && consecutiveCompactRounds >= maxConsecutiveCompactions()) {
                    return LoopResult.cancelled("too many consecutive context compactions");
                }
            }
        }

        // -----------------------------------------------------------------------------------
        // A round: before model -> model -> (no tools | tools -> promise | compaction | commit)
        // -----------------------------------------------------------------------------------

        private RoundOutcome runRound() throws Exception {
            LoopResult halt = beforeModel();
            if (halt != null) return RoundOutcome.finish(halt);

            ChatResponseEntity response;
            try {
                response = invokeModel();
            } catch (ExecutionInterruptedException e) {
                return RoundOutcome.finish(toLoopResult(e));
            }
            messages.response(response);

            halt = haltOf(interceptor.onAfterModelInvoke(loopContext, response));
            if (halt != null) return RoundOutcome.finish(halt);

            publishAiMessage(response);

            AiMessageEntity aiMessage = response.getAiMessageEntity();
            if (hasNoToolCall(aiMessage)) return RoundOutcome.finish(completeWithoutToolCalls());

            halt = haltOf(interceptor.onBeforeToolCall(loopContext));
            if (halt != null) return RoundOutcome.finish(halt);

            List<ToolExecuteResult> toolResults = executeTools(aiMessage, response.getResponseId());
            messages.toolResults(toolResults);

            halt = haltOf(interceptor.onAfterToolCall(loopContext, toolResults));
            if (halt != null) return RoundOutcome.finish(halt);

            if (hasPromise(toolResults)) return RoundOutcome.finish(suspendForPromise());

            if (applyCompaction()) {
                reportCompletedRound();
                consecutiveCompactRounds++;
                return RoundOutcome.COMPACTED;
            }

            appendMessages();
            consecutiveCompactRounds = 0;

            halt = haltOf(boundaryGuard.afterTools(execution, control));
            if (halt != null) return RoundOutcome.finish(halt);

            reportCompletedRound();
            return RoundOutcome.NEXT;
        }

        /** Loop-start and pre-model hooks, then the control boundary. Returns a result to stop, or null. */
        private @Nullable LoopResult beforeModel() {
            LoopResult halt = haltOf(interceptor.onLoopStart(loopContext));
            if (halt != null) return halt;

            halt = haltOf(interceptor.onBeforeModelInvoke(loopContext));
            if (halt != null) return halt;

            // External input must be present before compaction and budget checks run.
            return haltOf(boundaryGuard.beforeModel(execution, control));
        }

        /** The model answered without asking for tools: commit the round and finish, unless control says otherwise. */
        private LoopResult completeWithoutToolCalls() {
            appendMessages();
            consecutiveCompactRounds = 0;
            reportCompletedRound();

            LoopResult requested = resolveControlResult(control);
            if (requested != null) return requested;

            InterceptorResult beforeComplete = interceptor.onBeforeComplete(loopContext);

            // Re-checked on purpose: a control request raised while the interceptor ran still wins.
            requested = resolveControlResult(control);
            if (requested != null) return requested;

            return beforeComplete.shouldContinue() ? LoopResult.completed() : beforeComplete.loopResult();
        }

        /** A tool asked to suspend: commit the round, then suspend. Only a pending cancellation overrides that. */
        private LoopResult suspendForPromise() {
            appendMessages();
            consecutiveCompactRounds = 0;
            reportCompletedRound();

            LoopResult requested = resolveControlResult(control);
            if (requested != null && requested.status() == LoopResult.Status.CANCELLED) return requested;

            return LoopResult.suspended("tool result requested execution suspension");
        }

        // -----------------------------------------------------------------------------------
        // Model
        // -----------------------------------------------------------------------------------

        private @NonNull ChatResponseEntity invokeModel() throws Exception {
            String responseId = execution.nextResponseId(context.getResponseIdGenerator());
            try {
                ModelChatCommand command = modelRequests.build(execution, configuredTools(), responseId, control);
                ChatResponseEntity response = context.getInvoker().invoke(command);
                response.setResponseId(responseId);
                return response;
            } catch (Exception e) {
                // A control request reached the streaming handler while the model was still producing
                // output. The streaming invoker waits on a CompletableFuture, and CompletableFuture.join()
                // wraps whatever completed the future in a CompletionException. Unwrap it so the control
                // flow signal is recognized instead of being reported as an execution failure.
                ExecutionInterruptedException interrupted = findInterrupted(e);
                if (interrupted != null) throw interrupted;
                throw e;
            }
        }

        private void publishAiMessage(ChatResponseEntity response) {
            context.getRuntimeEventPublisher().onAiMessage(new AgentMessageEvent(
                    response,
                    execution.getId(),
                    response.getResponseId(),
                    execution.eventMetaData()
            ));
        }

        // -----------------------------------------------------------------------------------
        // Tools
        // -----------------------------------------------------------------------------------

        private List<ToolExecuteResult> executeTools(@NonNull AiMessageEntity aiMessage, String responseId) {
            AgentRequest request = execution.getAgentRequest();
            AgentRuntimeParameters params = request.runtimeParametersOrDefault();

            return context.getToolExecutionManager().execute(
                    new ToolExecuteCommand(
                            aiMessage.getToolCalls(),
                            execution.getId(),
                            context.getWorkspace(),
                            params.getAttributes(),
                            params.getEventMetaData(),
                            configuredTools(),
                            responseId,
                            params.isAllowOutsideWorkspace(),
                            context.getMcpToolScope(),
                            request.getSkillConfig()
                    )
            );
        }

        private @Nullable List<String> configuredTools() {
            return execution.getAgentRequest().getToolList();
        }

        // -----------------------------------------------------------------------------------
        // Compaction
        // -----------------------------------------------------------------------------------

        /**
         * Mixed batches keep all results; only a dedicated successful compact round replaces context.
         * The rebuilt context is what must survive the round: the compacted view is kept as the live
         * context while the compact round itself is appended only to the transcript, so the model is
         * served the summary instead of the history it replaces.
         */
        private boolean applyCompaction() {
            List<ToolExecuteResult> results = messages.getToolExecuteResults();
            if (results == null || results.size() != 1) return false;
            if (messages.getResponse().getAiMessageEntity().getToolCalls().size() != 1) return false;

            ToolExecuteResult result = results.getFirst();
            if (!result.isSuccess() || result.getToolResultType() != ToolResultType.CONTEXT_COMPACT)
                return false;

            boolean applied = new CompactSummaryApplier(context.getConversationManager())
                    .apply(result.getToolOutput(), execution, true);
            if (!applied) return false;

            appendCompactRoundToTranscriptOnly();
            publishCompactionCompleted();
            return true;
        }

        /**
         * Records the compact round in the transcript and accounting only: it must not stay in the
         * model context, so the rebuilt view is restored afterward.
         */
        private void appendCompactRoundToTranscriptOnly() {
            List<Message> compacted = execution.getMessages();
            execution.setMessages(new ArrayList<>(compacted));
            try {
                appendMessages();
            } finally {
                execution.setMessages(compacted);
            }
        }

        private void publishCompactionCompleted() {
            if (context.getUsage() != null) {
                context.getUsage().publish(execution,
                        ContextUpdateEvent.Phase.SQUEEZE_COMPLETED, "Tool compaction completed");
            }
        }

        // -----------------------------------------------------------------------------------
        // Persistence / accounting
        // -----------------------------------------------------------------------------------

        private void appendMessages() {
            context.getConversationManager().addMessage(messages);
        }

        private void reportCompletedRound() {
            if (context.getUsage() != null) context.getUsage().afterRound(execution);
            if (context.getExecutionRepository() != null) context.getExecutionRepository().save(execution);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Stateless helpers
    // ---------------------------------------------------------------------------------------

    /** The result to stop the loop with, or null when the interceptor lets the round carry on. */
    private static @Nullable LoopResult haltOf(InterceptorResult result) {
        return result.shouldContinue() ? null : result.loopResult();
    }

    /** The result to stop the loop with, or null when the boundary lets the round carry on. */
    private static @Nullable LoopResult haltOf(LoopResult result) {
        return result.shouldContinue() ? null : result;
    }

    private static @Nullable LoopResult resolveControlResult(@NonNull ExecutionControlSignal control) {
        if (control.isCancelRequired()) {
            return LoopResult.cancelled("execution cancellation requested");
        }
        if (control.isSuspendRequired()) {
            return LoopResult.suspended("execution suspension requested");
        }
        return null;
    }

    private static LoopResult toLoopResult(ExecutionInterruptedException e) {
        return e.kind() == ExecutionInterruptedException.Kind.CANCEL
                ? LoopResult.cancelled(e.getMessage())
                : LoopResult.suspended(e.getMessage());
    }

    /**
     * Walks the cause chain looking for the control-flow signal raised by the streaming handler,
     * hiding the wrapper types added by {@link java.util.concurrent.CompletableFuture}. The walk
     * stops as soon as an unrelated exception shows up, so a genuine model failure keeps its own
     * identity and is still reported as a failure.
     */
    private static @Nullable ExecutionInterruptedException findInterrupted(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ExecutionInterruptedException interrupted) return interrupted;
            if (!isTransparentWrapper(cause)) return null;
        }
        return null;
    }

    private static boolean isTransparentWrapper(Throwable cause) {
        return cause instanceof CompletionException
                || cause instanceof ExecutionException
                || cause.getClass() == RuntimeException.class;
    }

    private static boolean hasNoToolCall(@NonNull AiMessageEntity response) {
        List<ToolCallRequest> toolCalls = response.getToolCalls();
        return toolCalls == null || toolCalls.isEmpty();
    }

    private static boolean hasPromise(List<ToolExecuteResult> toolResults) {
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
}
