package com.summit.runtime.conversation;

import com.summit.harnesscore.agent.AgentRequest;
import com.summit.harnesscore.compact.ContextCompacter;
import com.summit.harnesscore.compact.ContextSummary;
import com.summit.harnesscore.conversation.ConversationEntity;
import com.summit.harnesscore.conversation.ConversationManager;
import com.summit.harnesscore.conversation.ConversationStore;
import com.summit.harnesscore.conversation.api.ChatResponseEntity;
import com.summit.harnesscore.conversation.event.RuntimeEventPublisher;
import com.summit.harnesscore.conversation.message.AiMessageEntity;
import com.summit.harnesscore.conversation.message.Message;
import com.summit.harnesscore.conversation.message.SystemMessageEntity;
import com.summit.harnesscore.conversation.message.TokenUsageEntity;
import com.summit.harnesscore.conversation.message.ToolMessageEntity;
import com.summit.harnesscore.conversation.message.UserMessageEntity;
import com.summit.harnesscore.runtime.Workspace;
import com.summit.harnesscore.tool.ToolExecuteResult;
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
    private final ContextCompacter contextCompacter;


    @Override
    public void startConversation(AgentRequest agentRequest) {

        Optional<ConversationEntity> existing = this.conversationStore.get(agentRequest.sessionIdOrDefault());

        // if session has exist then append input
        if (existing.isPresent()) {
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
    public void squeezeContext(Integer expectedTokens, Integer attemptNum, Serializable sessionId) {
        ConversationEntity conversation = getConversationEntity(sessionId);
        this.contextCompacter.compact(expectedTokens, attemptNum, conversation.messages());
        this.conversationStore.save(sessionId, conversation);
    }

    @Override
    public void rebuildContext(ContextSummary contextSummary, Serializable sessionId) {
        if (contextSummary == null) return;
        ConversationEntity conversation = getConversationEntity(sessionId);
        String summary = contextSummary.getSummary();
        try {
            log.info("【context-rebuild】 rebuilding context with summary: {}", summary);
            SystemMessageEntity systemMessage = conversation.SystemMessageEntity();
            List<Message> latestToolMessageAndAiMessage = findLatestInteraction(conversation);
            conversation.messages().clear();
           conversation.messages().addAll(List.of(systemMessage, SystemMessageEntity.builder().text(
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
            ).build()));
            conversation.messages().addAll(latestToolMessageAndAiMessage);
            this.conversationStore.save(sessionId, conversation);
            log.info("【context-rebuild】successfully rebuild context with summary: {}", summary);
        } catch (Exception e) {
            log.error("【context-rebuild】 failed to rebuild context with summary: {}", summary, e);
        }
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
     * Get the system message for the given system prompt and workspace.
     * @param systemPrompt The system prompt to use.
     * @param workspace The workspace to use.
     * @return The system message.
     */
    private SystemMessageEntity getSystemMessage(String systemPrompt, Workspace workspace) {
        return SystemMessageEntity.builder().text(String.format(systemPrompt,
                workspace.runtimeEnvironment().osType(),
                workspace.workDir()
        )).build();

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
        SystemMessageEntity systemMessage =  getSystemMessage(agentRequest.getSystemPrompt(), agentRequest.getWorkspace());
        ConversationEntity conversation = ConversationEntity.empty(agentRequest.getSessionName(), agentRequest.getWorkspace(), systemMessage, sessionId);
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
