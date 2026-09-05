package com.summit.runtime.conversation;

import com.summit.core.agent.AgentRequest;
import com.summit.core.compact.ContextSummary;
import com.summit.core.conversation.ConversationEntity;
import com.summit.core.conversation.ConversationManager;
import com.summit.core.conversation.ConversationStore;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.conversation.message.TokenUsageEntity;
import com.summit.core.conversation.message.ToolMessageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.plan.PlanDecision;
import com.summit.core.plan.PlanEntity;
import com.summit.core.plan.PlanStepStatus;
import com.summit.core.runtime.Workspace;
import com.summit.core.tool.LoopBoundary;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.runtime.plan.PlanCoordinator;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

import java.io.Serializable;
import java.util.*;



@Getter
@Slf4j
@AllArgsConstructor
public class DefaultConversationManager implements ConversationManager {
    private final ConversationStore conversationStore;
    private final RuntimeEventPublisher runtimeEventPublisher;
    private final SystemPromptAssembler systemPromptAssembler;
    private final String defaultSystemPrompt;
    private final PlanCoordinator planCoordinator;


    @Override
    public void startConversation(AgentRequest agentRequest) {

        Optional<ConversationEntity> existing = this.conversationStore.get(agentRequest.sessionIdOrDefault());

        // if session has existed then refresh the leading system message and append input
        if (existing.isPresent()) {
            refreshSystemMessage(agentRequest, existing.get());
            appendNewUserMessageToConversation(agentRequest, existing.get());
            return;
        }

        // New session: create system + this input
        startNewConversation(agentRequest);
    }

    @Override
    public void addMessage(Serializable sessionId, ChatResponseEntity chatResponse, @Nullable List<ToolExecuteResult> toolExecutionResultMessage) {
        AiMessageEntity aiMessage = chatResponse.getAiMessageEntity();
        ConversationEntity conversation = getConversationEntity(sessionId);
        conversation.messages().add(aiMessage);
        conversation.tokenUsageEntity().add(chatResponse.getTokenUsage());

        addToolMessages(toolExecutionResultMessage, conversation);

        this.conversationStore.save(sessionId, conversation);
    }

    @Override
    public ConversationEntity endConversation(Serializable sessionId) {
        //Only the current execution is ended. The session is retained in the store for subsequent executions with the same sessionId to reuse the history
        return this.conversationStore.get(sessionId).orElse(null);
    }

    @Override
    public List<Message> messages(Serializable sessionId) {
        return Collections.unmodifiableList(getConversationEntity(sessionId).messages());
    }

    @Override
    public Workspace workspace(Serializable sessionId) {
        // The workspace stored at session start — the one supplied by the AgentRequest
        return this.conversationStore.get(sessionId).map(ConversationEntity::workspace).orElse(null);
    }

    @Override
    public TokenUsageEntity tokenUsage(Serializable sessionId) {
        return this.conversationStore.get(sessionId).orElseThrow().tokenUsageEntity();
    }

    @Override
    public Optional<PlanDecision> capturePlan(Serializable sessionId, String executionId, String aiText, LoopBoundary boundary) {
        return this.planCoordinator.capture(sessionId, executionId, aiText, boundary);
    }

    @Override
    public Optional<PlanEntity> planOf(Serializable sessionId) {
        return this.planCoordinator.planOf(sessionId);
    }

    @Override
    public Optional<PlanEntity> appendPlanStep(Serializable sessionId, String description) {
        return this.planCoordinator.appendStep(sessionId, description);
    }

    @Override
    public Optional<PlanEntity> updatePlanSteps(Serializable sessionId, PlanStepStatus status) {
        return this.planCoordinator.markSteps(sessionId, status);
    }

    @Override
    public void refreshBoundary(Serializable sessionId, LoopBoundary boundary, String customSystemPrompt) {
        ConversationEntity conversation = getConversationEntity(sessionId);
        setLeadingSystemMessage(conversation,
                SystemMessageEntity.builder().text(
                        assembleSystemPrompt(conversation.workspace(), customSystemPrompt, boundary)
                ).build(),
                sessionId);
    }

    @Override
    public void rebuildContext(ContextSummary contextSummary, Serializable sessionId) {
        if (contextSummary == null) return;
        ConversationEntity conversation = getConversationEntity(sessionId);
        String summary = contextSummary.getSummary();
        try {
            log.info("【context-rebuild】 rebuilding context with summary: {}", summary);
            SystemMessageEntity systemMessage = conversation.systemMessageEntity();
            List<Message> latestToolMessageAndAiMessage = findLatestInteraction(conversation);
            String planText = planTextForRebuild(sessionId, conversation);

            List<Message> rebuilt = new ArrayList<>();
            rebuilt.add(systemMessage);
            // Protection: re-attach the session plan verbatim (or, as a fallback, the first
            // pure-text AI message of the history) so the produced plan survives compaction.
            if (planText != null && !planText.isBlank()) {
                rebuilt.add(AiMessageEntity.builder().text(planText).build());
            }
            rebuilt.add(SystemMessageEntity.builder().text(
                    String.format("""
                                    The compact_context tool has been executed successfully, and the conversation history has been compressed into the following summary:
                                    goal: \n
                                    %s
                                    summary: \n
                                    %s
                                    completed task: \n
                                    %s
                                    pending task: \n
                                    %s
                                    summary-task state: \n
                                    %s
                                    Continue the conversation based on this summary. Do NOT execute anything about this summary
                                    """,
                            contextSummary.getGoal(),
                            contextSummary.getSummary(),
                            contextSummary.getCompleted(),
                            contextSummary.getPending(),
                            contextSummary.getState()
                    )
            ).build());
            rebuilt.addAll(latestToolMessageAndAiMessage);

            conversation.messages().clear();
            conversation.messages().addAll(rebuilt);
            this.conversationStore.save(sessionId, conversation);
            log.info("【context-rebuild】successfully rebuild context with summary: {}", summary);
        } catch (Exception e) {
            log.error("【context-rebuild】 failed to rebuild context with summary: {}", summary, e);
        }
    }

    /**
     * Resolves the plan text to re-attach after a context rebuild:
     * 1. the raw plan stored in the session {@link PlanStore}, when present;
     * 2. otherwise the first pure-text (tool-call-free) AI message in the history
     *    (the message that originally proposed the plan).
     */
    private String planTextForRebuild(Serializable sessionId, ConversationEntity conversation) {
        Optional<PlanEntity> plan = this.planCoordinator.planOf(sessionId);
        if (plan.isPresent() && plan.get().text() != null && !plan.get().text().isBlank()) {
            return plan.get().text();
        }
        for (Message message : conversation.messages()) {
            if (message instanceof AiMessageEntity ai
                    && (ai.getToolCalls() == null || ai.getToolCalls().isEmpty())
                    && ai.text() != null && !ai.text().isBlank()) {
                return ai.text();
            }
        }
        return null;
    }


    /**
     * Add tool messages to the conversation.
     * @param results The tool execution results.
     * @param conversation The conversation entity.
     */
    private void addToolMessages(List<ToolExecuteResult> results,ConversationEntity conversation) {
        if (results == null || results.isEmpty()) {
            return;
        }

        for (ToolExecuteResult result : results) {
            var toolDefinition = result.getToolSpecification();

            ToolMessageEntity message = ToolMessageEntity.builder()
                    .id(result.getId())
                    .name(toolDefinition == null
                            ? "unknown tool"
                            : toolDefinition.name())
                    .text(result.getToolOutput())
                    .build();

            conversation.messages().add(message);
        }
    }


    /**
     * Find the latest interaction in the conversation.
     * @param conversation The conversation entity.
     * @return The latest interaction in the conversation.
     */
    private List<Message> findLatestInteraction(ConversationEntity conversation) {

        List<Message> result = new ArrayList<>();

        for (int i = conversation.messages().size() - 1; i >= 0; i--) {

            Message message = conversation.messages().get(i);

            if (message instanceof ToolMessageEntity) {
                result.addFirst(message);
                continue;
            }

            if (message instanceof AiMessageEntity) {
                result.addFirst(message);
                break;
            }
        }

        return result;
    }


    /**
     * Get the assembled system message for the given agent request.
     * The three-part prompt (default template + custom prompt + loop boundary)
     * is assembled by {@link SystemPromptAssembler}.
     */
    /**
     * Assembles the three-part system prompt text for the given workspace / custom
     * prompt / loop boundary (only the default template is formatted).
     */
    private String assembleSystemPrompt(Workspace workspace, String customSystemPrompt, LoopBoundary boundary) {
        return this.systemPromptAssembler.assemble(this.defaultSystemPrompt, workspace, customSystemPrompt, boundary);
    }

    private SystemMessageEntity buildSystemMessage(AgentRequest agentRequest) {
        return SystemMessageEntity.builder().text(
                assembleSystemPrompt(agentRequest.getWorkspace(), agentRequest.getSystemPrompt(), agentRequest.getLoopBoundary())
        ).build();
    }

    /**
     * Refresh the leading system message of an existing conversation so the current
     * loop boundary / custom prompt of this request is visible to the model.
     *
     * <p>Only rebuilds when the assembled text actually changed. Legacy conversations
     * whose leading message is not a system message get the system message inserted
     * at index 0. The {@link ConversationEntity#systemMessageEntity} field is kept in
     * sync so later context rebuilds reuse the same message.</p>
     */
    private void refreshSystemMessage(AgentRequest agentRequest, ConversationEntity conversation) {
        setLeadingSystemMessage(conversation, buildSystemMessage(agentRequest), agentRequest.sessionIdOrDefault());
    }

    /**
     * Puts the given system message at index 0 of the conversation message stream
     * (replacing an existing leading system message, or inserting one for legacy
     * conversations), keeps {@link ConversationEntity#systemMessageEntity} in sync and
     * persists the change. No-op when the leading message already carries the same text.
     */
    private void setLeadingSystemMessage(ConversationEntity conversation, SystemMessageEntity systemMessage, Serializable sessionId) {
        List<Message> messages = conversation.messages();
        Message first = messages.isEmpty() ? null : messages.get(0);
        if (first instanceof SystemMessageEntity existing && existing.text().equals(systemMessage.text())) {
            return;
        }
        if (first instanceof SystemMessageEntity) {
            messages.set(0, systemMessage);
        } else {
            messages.add(0, systemMessage);
        }
        ConversationEntity refreshed = new ConversationEntity(conversation.sessionId(), conversation.sessionName(),
                messages, conversation.tokenUsageEntity(), systemMessage, conversation.workspace());
        this.conversationStore.save(sessionId, refreshed);
    }

    /**
     * Get the conversation entity for the given session ID.
     * @param sessionId The session ID.
     * @return The conversation entity.
     */
    private ConversationEntity getConversationEntity(Serializable sessionId) {
        return this.conversationStore.get(sessionId).orElseThrow();
    }

    /**
     * Start a new conversation with the given agent request.
     * @param agentRequest The agent request containing the system prompt and workspace.
     */
    private void startNewConversation(AgentRequest agentRequest){
        Serializable sessionId = agentRequest.sessionIdOrDefault();
        SystemMessageEntity systemMessage = buildSystemMessage(agentRequest);
        ConversationEntity conversation = ConversationEntity.empty(agentRequest.getSessionName(), agentRequest.getWorkspace(), systemMessage, sessionId);
        // The system message is part of the model message stream (index 0), matching the
        // rebuildContext layout, so the assembled boundary prompt always reaches the model.
        conversation.messages().add(systemMessage);
        conversation.messages().add(UserMessageEntity.from(agentRequest.getInput()));
        this.conversationStore.save(sessionId, conversation);
    }


    /**
     * append a new user message to the conversation and set the sessionName if it is not set
     * @param agentRequest The agent request containing the input and session name.
     * @param conversation The conversation entity to append the user message to.
     */
    private void appendNewUserMessageToConversation(AgentRequest agentRequest,ConversationEntity conversation){
        List<Message> messages = conversation.messages();
        Serializable sessionId = agentRequest.sessionIdOrDefault();
        String sessionName = agentRequest.getSessionName();
        UserMessageEntity userMessage = UserMessageEntity.from(agentRequest.getInput());
        Message last = messages.isEmpty() ? null : messages.getLast();

        // Idempotent protection: When the last execution fails and is retried, the last message may already be the current input, avoiding duplicate appending
        if (last == null || !last.text().equals(userMessage.text())) {
            messages.add(userMessage);
            this.conversationStore.save(sessionId, conversation);
        }

        // Backfill the session name on first sight if the caller supplied one
        if (sessionName != null && !sessionName.isBlank()
                && (conversation.sessionName() == null || conversation.sessionName().isBlank())) {
            this.conversationStore.save(sessionId, conversation.withSessionName(sessionName));
        }
    }

}
