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
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.model.ModelChatCommand;
import com.summit.core.plan.PlanDecision;
import com.summit.core.plan.PlanStepStatus;
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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Main agent loop of a single execution: repeatedly asks the model and writes tool results back to
 * the conversation until the model finishes with plain text, the plan is not approved, or a lifecycle
 * checkpoint demands a stop. Per-run state (plan auto-execution, plain-text closure, whether a write
 * tool actually ran) is consumed by the runtime in its finalisation phase.
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

    public void run(Execution execution, Serializable sessionId) {
        while (true) {
            if (!context.getCheckPointer().beforeCheckpoint(execution)) {
                log.warn("【agent-loop】process is stopped due to notConforming condition: {}", execution.getId());
                break;
            }

            ChatResponseEntity chatResponse = context.getInvoker().invoke(buildRequest(execution));
            log.info("【Agent】:{} thinking:{}", chatResponse.getAiMessageEntity().text(), chatResponse.getAiMessageEntity().getThinking());
            context.getRuntimeEventPublisher().onAiMessage(new AgentMessageEvent(sessionId,
                    chatResponse.getAiMessageEntity().text(), chatResponse.getAiMessageEntity().getThinking(), execution.getId()));

            if (hasNoToolCall(chatResponse)) {
                if (handlePlainTextTurn(execution, sessionId, chatResponse)) {
                    continue;   // plan approved: keep looping under the EXECUTE boundary
                }
                break;
            }

            if (handleToolCallTurn(execution, sessionId, chatResponse)) {
                continue;       // model called compact_context: this round is not stored, go to next round
            }
            if (!context.getCheckPointer().afterCheckpoint(execution)) {
                log.warn("【agent-loop】process is stopped due to lifestyle changed: {}", execution.getId());
                break;
            }
        }
    }

    /**
     * Plain-text turn: a PLANNING execution captures the message as a plan and waits for human approval;
     * otherwise the execution closes normally with plain text.
     *
     * @return true when the plan was approved and the loop should keep running
     */
    private boolean handlePlainTextTurn(Execution execution, Serializable sessionId, ChatResponseEntity chatResponse) {
        AiMessageEntity aiMessage = chatResponse.getAiMessageEntity();
        // Only the first plain-text turn of a PLANNING execution counts as a produced plan;
        // once auto-execution starts, plain text means the execution has finished.
        Optional<PlanDecision> captured = autoExecute
                ? Optional.empty()
                : capturePlanIfPlanning(execution, sessionId, aiMessage);
        captured.ifPresent(execution::setPlanDecision);
        context.getConversationManager().addMessage(sessionId, chatResponse, null);

        if (captured.isEmpty()) {
            closedByPlainText = true;
            return false;
        }

        PlanApprovalWaiter.PlanApprovalOutcome outcome = new PlanApprovalWaiter(context.getPlanApprovalRegistry())
                .await(execution, sessionId, captured.get().title(), aiMessage.text());
        if (outcome == PlanApprovalWaiter.PlanApprovalOutcome.APPROVED) {
            AgentRequest request = execution.getAgentRequest();
            context.getConversationManager().refreshBoundary(sessionId, LoopBoundary.EXECUTE,
                    request == null ? null : request.getSystemPrompt());
            context.getConversationManager().updatePlanSteps(sessionId, PlanStepStatus.IN_PROGRESS);
            log.info("【agent-loop】plan approved, agent implements it under EXECUTE boundary: executionId={}", execution.getId());
            autoExecute = true;
            return true;
        }
        if (outcome == PlanApprovalWaiter.PlanApprovalOutcome.INTERRUPTED) {
            execution.cancel();
            return false;
        }
        log.info("【agent-loop】plan not approved ({}), execution finished without implementing: executionId={}",
                outcome, execution.getId());
        return false;
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

    /** When a PLANNING execution ends with plain text, capture that text as the plan produced this round. */
    private Optional<PlanDecision> capturePlanIfPlanning(Execution execution, Serializable sessionId, AiMessageEntity aiMessage) {
        AgentRequest agentRequest = execution.getAgentRequest();
        LoopBoundary boundary = agentRequest == null ? null : agentRequest.getLoopBoundary();
        if (boundary != LoopBoundary.PLANING) {
            return Optional.empty();
        }
        if (aiMessage == null || aiMessage.text() == null || aiMessage.text().isBlank()) {
            return Optional.empty();
        }
        return context.getConversationManager()
                .capturePlan(sessionId, execution.getId(), aiMessage.text(), boundary);
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
     * Tools exposed to the model for the current loop round. Under the PLANING boundary only read-only
     * tools are passed, so the model cannot issue write calls while planning; otherwise (EXECUTE or
     * absent) the full set is passed. After approval {@code autoExecute=true} restores the full set for
     * later requests; the runtime {@code ToolExecutionContext#allowToolExecution} interceptor remains
     * as a backstop.
     */
    private List<ToolDefinition<?>> resolveRequestTools(Execution execution) {
        AgentRequest agentRequest = execution.getAgentRequest();
        LoopBoundary boundary = autoExecute
                ? LoopBoundary.EXECUTE
                : (agentRequest == null ? null : agentRequest.getLoopBoundary());
        List<ToolDefinition<?>> allTools = this.context.getToolExecutionManager().toolRegistry().getTools().values().stream()
                .<ToolDefinition<?>>map(tool -> tool)
                .toList();
        if (LoopBoundary.allowExecute(boundary)) {
            return allTools;
        }
        return allTools.stream()
                .filter(ToolDefinition::readOnly)
                .toList();
    }
}
