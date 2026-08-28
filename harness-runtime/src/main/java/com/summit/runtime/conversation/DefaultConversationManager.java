package com.summit.runtime.conversation;

import com.summit.harnesscore.agent.AgentRequest;
import com.summit.harnesscore.compact.ContextCompacter;
import com.summit.harnesscore.compact.ContextSummary;
import com.summit.harnesscore.conversation.ConversationEntity;
import com.summit.harnesscore.conversation.ConversationManager;
import com.summit.harnesscore.conversation.ConversationStore;
import com.summit.harnesscore.conversation.event.RuntimeEventPublisher;
import com.summit.harnesscore.runtime.Workspace;
import com.summit.harnesscore.tool.ToolExecuteResult;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
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
        Serializable sessionId = agentRequest.getSessionId();
        String systemPrompt = getSystemMessage(agentRequest.getSystemPrompt(), agentRequest.getWorkspace());
        SystemMessage systemMessage = SystemMessage.from(systemPrompt);
        LinkedList<ChatMessage> messages = new LinkedList<>(
                List.of(
                        systemMessage,
                        UserMessage.from(agentRequest.getInput())
                )
        );
        ConversationEntity conversation = ConversationEntity.empty(agentRequest.getWorkspace(), systemMessage, sessionId, messages);
        this.conversationStore.save(agentRequest.getSessionId(), conversation);
    }

    @Override
    public void addMessage(Serializable sessionId, ChatResponse chatResponse, @Nullable List<ToolExecuteResult> toolExecutionResultMessage) {
        AiMessage aiMessage = chatResponse.aiMessage();
        ConversationEntity conversation = getConversationEntity(sessionId);
        conversation.messages().add(aiMessage);
        conversation.tokenUsage().add(chatResponse.tokenUsage());

        addToolMessages(toolExecutionResultMessage,conversation);
    }

    @Override
    public ConversationEntity endConversation(Serializable sessionId) {
        return this.conversationStore.remove(sessionId);
    }

    @Override
    public List<ChatMessage> messages(Serializable sessionId) {
        return Collections.unmodifiableList(getConversationEntity(sessionId).messages());
    }

    @Override
    public TokenUsage tokenUsage(Serializable sessionId) {
        return this.conversationStore.get(sessionId).orElseThrow().tokenUsage();
    }

    @Override
    public void squeezeContext(Integer expectedTokens, Integer attemptNum, Serializable sessionId) {
        ConversationEntity conversation = getConversationEntity(sessionId);
        this.contextCompacter.compact(expectedTokens, attemptNum, conversation.messages());
    }

    @Override
    public void rebuildContext(ContextSummary contextSummary, Serializable sessionId) {
        if (contextSummary == null) return;
        ConversationEntity conversation = getConversationEntity(sessionId);
        String summary = contextSummary.getSummary();
        try {
            log.info("【context-rebuild】 rebuilding context with summary: {}", summary);
            SystemMessage systemMessage = conversation.systemMessage();
            List<ChatMessage> latestToolMessageAndAiMessage = findLatestInteraction(conversation);
            conversation.messages().clear();
           conversation.messages().addAll(List.of(systemMessage, SystemMessage.from(
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
            )));
            conversation.messages().addAll(latestToolMessageAndAiMessage);
            log.info("【context-rebuild】successfully rebuild context with summary: {}", summary);
        } catch (Exception e) {
            log.error("【context-rebuild】 failed to rebuild context with summary: {}", summary, e);
        }
    }


    private void addToolMessages(List<ToolExecuteResult> results,ConversationEntity conversation) {
        if (results == null || results.isEmpty()) {
            return;
        }

        for (ToolExecuteResult result : results) {
            ToolSpecification specification = result.getToolSpecification();

            ToolExecutionResultMessage message =
                    ToolExecutionResultMessage.toolExecutionResultMessage(
                            result.getId(),
                            specification == null
                                    ? "unknown tool"
                                    : specification.name(),
                            result.getToolOutput()
                    );

            conversation.messages().add(message);
        }
    }


    private List<ChatMessage> findLatestInteraction(ConversationEntity conversation) {

        List<ChatMessage> result = new ArrayList<>();

        for (int i = conversation.messages().size() - 1; i >= 0; i--) {

            ChatMessage message = conversation.messages().get(i);

            if (message instanceof ToolExecutionResultMessage) {
                result.addFirst(message);
                continue;
            }

            if (message instanceof AiMessage) {
                result.addFirst(message);
                break;
            }
        }

        return result;
    }



    private String getSystemMessage(String systemPrompt, Workspace workspace) {
        return String.format(systemPrompt,
                workspace.runtimeEnvironment().osType(),
                workspace.workDir()
        );
    }

    private ConversationEntity getConversationEntity(Serializable sessionId) {
        return this.conversationStore.get(sessionId).orElseThrow();
    }

}
