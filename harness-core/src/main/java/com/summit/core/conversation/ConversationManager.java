package com.summit.core.conversation;

import com.summit.core.agent.AgentRequest;
import com.summit.core.compact.ContextSummary;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.TokenUsageEntity;
import com.summit.core.plan.PlanDecision;
import com.summit.core.plan.PlanEntity;
import com.summit.core.plan.PlanStepStatus;
import com.summit.core.runtime.Workspace;
import com.summit.core.tool.LoopBoundary;
import com.summit.core.tool.ToolExecuteResult;


import java.io.Serializable;
import java.util.List;
import java.util.Optional;

public interface ConversationManager {

    void startConversation(AgentRequest agentRequest);

    void addMessage(Serializable sessionId, ChatResponseEntity chatResponse,  List<ToolExecuteResult> toolExecutionResultMessage);
    ConversationEntity endConversation(Serializable sessionId);

    List<Message> messages(Serializable sessionId);

    /**
     * Returns the workspace bound to the session — the instance supplied by
     * the {@code AgentRequest} that started it. There is no global fallback;
     * consumers (e.g. patch application) must use this per-session workspace.
     *
     * @return the session workspace, or {@code null} when the session is unknown
     */
    Workspace workspace(Serializable sessionId);

    TokenUsageEntity tokenUsage(Serializable sessionId);

    void rebuildContext(ContextSummary contextSummary, Serializable sessionId);

    // ------------------------------------------------------------------
    // Plan lifecycle (session-scoped). Default no-op methods keep every
    // existing implementation / caller intact; DefaultConversationManager
    // overrides them to delegate to the session PlanStore.
    // ------------------------------------------------------------------

    /**
     * Registers the plan produced at the end of a PLANNING execution as the current
     * session plan (upsert). Publishing the structured decision is left to the manager.
     *
     * @return the parsed structured plan decision when captured successfully
     */
    default Optional<PlanDecision> capturePlan(Serializable sessionId, String executionId, String aiText, LoopBoundary boundary) {
        return Optional.empty();
    }

    /**
     * Returns the plan currently registered for the session, if any.
     */
    default Optional<PlanEntity> planOf(Serializable sessionId) {
        return Optional.empty();
    }

    /**
     * Appends a new implementation step to the session plan, if one exists.
     */
    default Optional<PlanEntity> appendPlanStep(Serializable sessionId, String description) {
        return Optional.empty();
    }

    /**
     * Transitions all steps of the session plan to the given status, reflecting the
     * execution-time progress of the plan (PENDING → IN_PROGRESS → COMPLETED). The
     * plan-level {@code PlanState} machine is advanced on the same transitions
     * (IN_PROGRESS -&gt; APPROVED, COMPLETED -&gt; COMPLETED), so no separate state call
     * is needed.
     *
     * @return the updated plan, or empty when the session has no plan
     */
    default Optional<PlanEntity> updatePlanSteps(Serializable sessionId, PlanStepStatus status) {
        return Optional.empty();
    }

    /**
     * Refreshes the leading system message of an existing session so the model sees the
     * given loop boundary. Used when a PLANNING execution has just produced a plan and
     * keeps running in the same loop to implement it under the EXECUTE boundary. Default
     * no-op keeps existing implementations intact.
     *
     * @param sessionId          the session whose system message should be refreshed
     * @param boundary           the boundary the model should now operate under
     * @param customSystemPrompt the caller-supplied custom prompt of the current request
     *                           ({@code null} when absent)
     */
    default void refreshBoundary(Serializable sessionId, LoopBoundary boundary, String customSystemPrompt) {
    }
}
