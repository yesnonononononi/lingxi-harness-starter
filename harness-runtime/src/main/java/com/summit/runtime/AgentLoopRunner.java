package com.summit.runtime;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.compact.CompactSummaryResolver;
import com.summit.core.compact.ContextSummary;
import com.summit.core.conversation.api.ChatRequestEntity;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.context.RuntimeContext;
import com.summit.core.conversation.event.AgentMessageEvent;
import com.summit.core.internalUtils.PlanLoopHook;
import com.summit.core.internalUtils.PlanLoopHook.PlanTurnAction;
import com.summit.core.internalUtils.PlanTurnResult;
import com.summit.core.model.ModelChatCommand;
import com.summit.core.tool.LoopBoundary;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecuteCommand;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolResultType;
import com.summit.runtime.model.StreamingModelResponseBehaveDecider;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Main agent loop of a single execution: repeatedly asks the model and writes tool results back to
 * the conversation until the model finishes with plain text, the plan lifecycle asks to stop, or a
 * lifecycle checkpoint demands a stop. Per-run state (plan auto-execution, plain-text closure,
 * whether a write tool actually ran) is consumed by the runtime in its finalisation phase.
 *
 * <p>The plan mode is reached exclusively through {@link PlanLoopHook} (three call sites), so this
 * class contains no plan vocabulary: it injects the directive the hook returns, switches boundary
 * when asked to, and stops when told to.</p>
 *
 * <p>Context compaction is neither decided nor performed here: the squeeze band is judged in
 * {@code CheckPointer#afterCheckpoint} and the actual work is done in a blocking way by a
 * {@code ContextCompacter} implementation (manual truncation / model compaction). This class only
 * reconciles the rebuilt result when the model itself called the {@code compact_context} tool.</p>
 */
@RequiredArgsConstructor
@Slf4j
public class AgentLoopRunner {

    private final RuntimeContext context;

    private boolean autoExecute;
    @Getter
    private boolean executedWriteSuccessfully;
    @Getter
    private boolean closedByPlainText;
    /** Set when the plan lifecycle asked the loop to end this execution (plan rejected / cancelled). */
    private boolean planTurnStopped;

    public void run(Execution execution, Serializable sessionId) {
        while (true) {
            if (!context.getCheckPointer().beforeCheckpoint(execution)) {
                log.warn("【agent-loop】process is stopped due to notConforming condition: {}", execution.getId());
                break;
            }

            ChatResponseEntity chatResponse = context.getInvoker().invoke(buildRequest(execution));
            context.getRuntimeEventPublisher().onAiMessage(new AgentMessageEvent(sessionId,
                    chatResponse.getAiMessageEntity().text(), chatResponse.getAiMessageEntity().getThinking(), execution.getId()));

            if (hasNoToolCall(chatResponse)) {
                if (handlePlainTextTurn(execution, sessionId, chatResponse)) {
                    continue;   // approved plan under implementation: keep looping
                }
                break;
            }

            if (handleToolCallTurn(execution, sessionId, chatResponse)) {
                continue;       // model called compact_context: this round is not stored, go to next round
            }
            if (planTurnStopped) {
                log.info("【agent-loop】execution stopped by the plan lifecycle: {}", execution.getId());
                break;
            }
            if (!context.getCheckPointer().afterCheckpoint(execution)) {
                log.warn("【agent-loop】process is stopped due to lifestyle changed: {}", execution.getId());
                break;
            }
        }
    }

    /**
     * Plain-text turn: the round is stored, then the plan hook decides whether the execution may close.
     * A plan is never created here anymore — plans are produced by the {@code create_plan} kernel tool —
     * the only plan concern left is "an approved plan still has open tasks, do not close yet".
     *
     * @return true when the loop should keep running
     */
    private boolean handlePlainTextTurn(Execution execution, Serializable sessionId, ChatResponseEntity chatResponse) {
        context.getConversationManager().addMessage(sessionId, chatResponse, null);

        PlanTurnAction action = applyPlanTurn(execution, sessionId, planHook().onPlainTextTurn(execution, sessionId));
        if (action == PlanTurnAction.CONTINUE || action == PlanTurnAction.SWITCH_TO_EXECUTE) {
            return true;
        }
        if (action == PlanTurnAction.NONE) {
            closedByPlainText = true;
        }
        return false;
    }

    /**
     * Applies the decision of the plan hook: injects the directive it produced, refreshes the loop
     * boundary when the plan was approved, and records a stop / cancel request for the main loop.
     *
     * @return the action the hook decided
     */
    private PlanTurnAction applyPlanTurn(Execution execution, Serializable sessionId, PlanTurnResult turn) {
        PlanTurnAction action = turn == null ? PlanTurnAction.NONE : turn.action();
        if (turn != null && turn.hasDirective()) {
            context.getConversationManager().appendUserMessage(sessionId, turn.directive());
        }
        if (action == PlanTurnAction.SWITCH_TO_EXECUTE) {
            AgentRequest request = execution.getAgentRequest();
            context.getConversationManager().refreshBoundary(sessionId, LoopBoundary.EXECUTE,
                    request == null ? null : request.getSystemPrompt());
            autoExecute = true;
            log.info("【agent-loop】plan approved, implementing under EXECUTE boundary: executionId={}", execution.getId());
        } else if (action == PlanTurnAction.CANCEL) {
            planTurnStopped = true;
            execution.cancel();
            log.warn("【agent-loop】plan approval interrupted, execution cancelled: {}", execution.getId());
        } else if (action == PlanTurnAction.STOP) {
            planTurnStopped = true;
            log.info("【agent-loop】plan not approved, execution finishes without implementing it: {}", execution.getId());
        }
        return action;
    }

    /** The plan hook of this runtime, or the no-op hook when no plan kernel is wired. */
    private PlanLoopHook planHook() {
        PlanLoopHook hook = context.getPlanLoopHook();
        return hook == null ? PlanLoopHook.NOOP : hook;
    }

    /**
     * Tool-call turn: executes the tools under the current boundary, tracks whether the plan was really
     * implemented, handles a model-initiated {@code compact_context} call, and writes this round's tool
     * results back to the conversation.
     *
     * @return true when the model called compact_context and the conversation was rebuilt,
     *         so the after-round checkpoint is skipped
     */
    private boolean handleToolCallTurn(Execution execution, Serializable sessionId, ChatResponseEntity chatResponse) {
        AgentRequest agentRequest = execution.getAgentRequest();
        LoopBoundary commandBoundary = autoExecute
                ? LoopBoundary.EXECUTE
                : (agentRequest == null ? null : agentRequest.getLoopBoundary());
        List<ToolExecuteResult> toolResults = context.getToolExecutionManager().execute(
                new ToolExecuteCommand(
                        chatResponse.getAiMessageEntity().getToolCalls(),
                        execution.getId(),
                        sessionId,
                        context.getWorkspace(),
                        agentRequest == null ? null : agentRequest.getCommandConfirmLevel(),
                        commandBoundary));
        if (hasExecutedWriteTool(chatResponse, toolResults)) {
            executedWriteSuccessfully = true;
        }

        ToolExecuteResult contextCompact = toolResults.stream()
                .filter(this::isContextCompactRequest)
                .findFirst()
                .orElse(null);
        if (contextCompact != null) {
            context.getConversationManager().rebuildContext(resolveContextSummary(contextCompact), sessionId);
            return true;
        }

        context.getConversationManager().addMessage(sessionId, chatResponse, toolResults);

        // plan side effects come after the round is persisted, so an injected directive lands after the tool results
        applyPlanTurn(execution, sessionId, planHook().afterToolTurn(toolResults, execution, sessionId));
        return false;
    }

    /** True only when at least one non-readonly tool call succeeded this turn, i.e. the plan is really being implemented. */
    private boolean hasExecutedWriteTool(ChatResponseEntity chatResponse, List<ToolExecuteResult> toolResults) {
        List<ToolCallRequest> calls = chatResponse.getAiMessageEntity().getToolCalls();
        if (calls == null || calls.isEmpty()) {
            return false;
        }
        int paired = Math.min(calls.size(), toolResults.size());
        for (int i = 0; i < paired; i++) {
            ToolExecuteResult result = toolResults.get(i);
            if (result == null || !result.isSuccess()) {
                continue;
            }
            ToolDefinition<?> def = context.getToolExecutionManager().toolRegistry().getTool(calls.get(i).name());
            if (def != null && !def.readOnly()) {
                return true;
            }
        }
        return false;
    }

    private boolean hasNoToolCall(ChatResponseEntity chatResponse) {
        List<ToolCallRequest> toolCalls = chatResponse.getAiMessageEntity().getToolCalls();
        return toolCalls == null || toolCalls.isEmpty();
    }

    private boolean isContextCompactRequest(ToolExecuteResult toolExecuteResult) {
        return toolExecuteResult.getToolResultType().equals(ToolResultType.CONTEXT_COMPACT);
    }

    private ContextSummary resolveContextSummary(ToolExecuteResult toolExecuteResult) {
        try {
            ContextSummary summary = CompactSummaryResolver.resolve(toolExecuteResult.getToolOutput());
            if (summary == null) {
                log.warn("【context-summary】compact model returned no usable summary, context rebuild is skipped");
            }
            return summary;
        } catch (Exception unexpected) {
            log.error("【context-summary】Unexpected error occurred while resolving context summary", unexpected);
            return null;
        }
    }

    private ModelChatCommand buildRequest(Execution execution) {
        ModelChatCommand.ModelChatCommandBuilder builder = ModelChatCommand.builder()
                .chatRequest(
                        ChatRequestEntity.builder()
                                .messages(this.context.getConversationManager().messages(execution.getSessionId()))
                                .tools(resolveRequestTools(execution))
                                .build()
                ).thinking(execution.isThinking())
                .streaming(execution.isStreaming());

        if (execution.isStreaming()) {
            StreamingModelResponseBehaveDecider decider = new StreamingModelResponseBehaveDecider(this.context.getRuntimeEventPublisher(), StreamingModelResponseBehaveDecider.StreamingResponseContext.builder()
                    .sessionId(execution.getSessionId())
                    .executionId(execution.getId())
                    .agentId(execution.getAgentId())
                    .future(new CompletableFuture<>())
                    .build());
            builder.streamingChatResponseHandler(decider);
        }
        return builder.build();
    }

    /**
     * Tools exposed to the model for the current loop round:
     * <ul>
     *   <li>planning-only tools are hidden once the boundary allows execution, so a planning tool
     *       (e.g. {@code create_plan}) can never interrupt an implementation run;</li>
     *   <li>under the PLANING boundary only read-only tools are passed, so the model cannot issue
     *       write calls while planning; otherwise (EXECUTE or absent) the whole remaining set is
     *       passed. After approval {@code autoExecute=true} restores the write tools for later
     *       requests; the {@code ToolExecutionContext#allowToolExecution} interceptor remains as a
     *       backstop.</li>
     * </ul>
     */
    private List<ToolDefinition<?>> resolveRequestTools(Execution execution) {
        AgentRequest agentRequest = execution.getAgentRequest();
        LoopBoundary boundary = autoExecute
                ? LoopBoundary.EXECUTE
                : (agentRequest == null ? null : agentRequest.getLoopBoundary());
        boolean allowsExecute = LoopBoundary.allowExecute(boundary);
        List<ToolDefinition<?>> tools = this.context.getToolExecutionManager().toolRegistry().getTools().values().stream()
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
