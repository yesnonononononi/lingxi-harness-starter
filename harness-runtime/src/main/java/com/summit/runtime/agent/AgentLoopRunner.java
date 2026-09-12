package com.summit.runtime.agent;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.context.RuntimeContext;
import com.summit.core.conversation.event.AgentMessageEvent;
import com.summit.core.model.ModelChatCommand;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecuteCommand;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.runtime.*;
import com.summit.runtime.compact.ContextCompactReconciler;
import com.summit.runtime.conversation.ContextUsageReporter;
import com.summit.runtime.model.ModelRequestFactory;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.List;

/**
 * Main agent loop of a single execution: repeatedly asks the model and writes tool results back to
 * the conversation until the model finishes with plain text, the plan lifecycle asks to stop, or a
 * lifecycle checkpoint demands a stop.
 *
 * <p>This class only <em>drives</em> the loop; every surrounding concern has its own home:</p>
 * <ul>
 *   <li>{@link ModelRequestFactory} — what the request of a round contains (messages, tools, streaming);</li>
 *   <li>{@link PlanTurnCoordinator} — the plan lifecycle, and whether the loop may keep running;</li>
 *   <li>{@link LoopBoundaryResolver} — which boundary a round runs under;</li>
 *   <li>{@link ContextCompactReconciler} — a model-initiated {@code compact_context} call;</li>
 *   <li>{@link ContextUsageReporter} — the live context usage pushed to the front-end gauge.</li>
 * </ul>
 *
 * <p>Per-run state ({@code executedWriteSuccessfully} / {@code closedByPlainText}) is exposed to the
 * runtime, which consumes it in its finalization phase.</p>
 */
@Slf4j
public class AgentLoopRunner {

    private final RuntimeContext context;
    private final ModelRequestFactory requests;
    private final PlanTurnCoordinator planTurns;
    private final ContextCompactReconciler compaction;
    private final ContextUsageReporter usage;

    @Getter
    private boolean executedWriteSuccessfully;
    @Getter
    private boolean closedByPlainText;

    public AgentLoopRunner(RuntimeContext context) {
        this.context = context;
        this.requests = new ModelRequestFactory(context);
        this.planTurns = new PlanTurnCoordinator(context);
        this.compaction = new ContextCompactReconciler(context.getConversationManager());
        this.usage = new ContextUsageReporter(context);
    }

    public void run(Execution execution, Serializable sessionId) {
        while (true) {
            if (!context.getCheckPointer().beforeCheckpoint(execution)) {
                log.warn("【agent-loop】process is stopped due to notConforming condition: {}", execution.getId());
                break;
            }

            ChatResponseEntity chatResponse = context.getInvoker().invoke(requestOf(execution, sessionId));
            context.getRuntimeEventPublisher().onAiMessage(new AgentMessageEvent(sessionId,
                    chatResponse.getAiMessageEntity().text(), chatResponse.getAiMessageEntity().getThinking(), execution.getId()));

            if (hasNoToolCall(chatResponse)) {
                PlanTurnCoordinator.Decision decision = handlePlainTextTurn(execution, sessionId, chatResponse);
                if (decision == PlanTurnCoordinator.Decision.CONTINUE) {
                    continue;   // approved plan under implementation: keep looping
                }
                closedByPlainText = decision == PlanTurnCoordinator.Decision.CLOSE_NATURALLY;
                break;
            }

            if (handleToolCallTurn(execution, sessionId, chatResponse)) {
                continue;       // model called compact_context: this round is not stored, go to next round
            }
            if (planTurns.isStopped()) {
                log.info("【agent-loop】execution stopped by the plan lifecycle: {}", execution.getId());
                break;
            }
            if (!context.getCheckPointer().afterCheckpoint(execution)) {
                log.warn("【agent-loop】process is stopped due to lifestyle changed: {}", execution.getId());
                break;
            }

            usage.afterRound(sessionId, execution.getId());
        }

        usage.publish(sessionId, execution.getId());
    }

    /**
     * Plain-text turn: the round is stored, then the plan lifecycle decides whether the execution may
     * close. A plan is never created here — plans come from the {@code create_plan} kernel tool — the
     * only plan concern left is "an approved plan still has open tasks, do not close yet".
     */
    private PlanTurnCoordinator.Decision handlePlainTextTurn(Execution execution, Serializable sessionId, ChatResponseEntity chatResponse) {
        context.getConversationManager().addMessage(sessionId, chatResponse, null);
        return planTurns.onPlainTextTurn(execution, sessionId);
    }

    /**
     * Tool-call turn: executes the tools under the current boundary, records whether a write tool
     * really ran, reconciles a model-initiated {@code compact_context} call, and writes this round's
     * tool results back to the conversation.
     *
     * @return true when the model called compact_context and the conversation was rebuilt, so the
     *         after-round checkpoint is skipped
     */
    private boolean handleToolCallTurn(Execution execution, Serializable sessionId, ChatResponseEntity chatResponse) {
        AgentRequest agentRequest = execution.getAgentRequest();
        List<ToolExecuteResult> toolResults = context.getToolExecutionManager().execute(
                new ToolExecuteCommand(
                        chatResponse.getAiMessageEntity().getToolCalls(),
                        execution.getId(),
                        sessionId,
                        context.getWorkspace(),
                        agentRequest == null ? null : agentRequest.getCommandConfirmLevel(),
                        LoopBoundaryResolver.resolve(execution, planTurns.isPlanApproved())));
        if (hasExecutedWriteTool(chatResponse, toolResults)) {
            executedWriteSuccessfully = true;
        }
        if (compaction.reconcile(toolResults, sessionId)) {
            return true;
        }

        context.getConversationManager().addMessage(sessionId, chatResponse, toolResults);

        // plan side effects come after the round is persisted, so an injected directive lands after the tool results
        planTurns.afterToolTurn(execution, sessionId, toolResults);
        return false;
    }

    /**
     * The request of one round: conversation, allowed tools and streaming, under the effective boundary.
     */
    private ModelChatCommand requestOf(Execution execution, Serializable sessionId) {
        return requests.build(execution, sessionId, LoopBoundaryResolver.resolve(execution, planTurns.isPlanApproved()));
    }

    /**
     * True only when at least one non-readonly tool call succeeded this turn, i.e. the plan is really being implemented.
     */
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
}
