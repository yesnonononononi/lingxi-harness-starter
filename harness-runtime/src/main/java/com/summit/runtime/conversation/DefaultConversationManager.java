package com.summit.runtime.conversation;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.compact.ContextAttachmentProvider;
import com.summit.core.compact.ContextSummary;
import com.summit.core.conversation.ConversationManager;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.api.ConversationTranscriptSink;
import com.summit.core.conversation.message.*;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.tool.ToolExecuteResult;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stateless operations over the context owned by one execution.
 */
public class DefaultConversationManager implements ConversationManager {
    private static final String CONTINUE_AFTER_COMPACTION_PROMPT = """
            The conversation history before this point has been compacted into the summary above.
            Continue the work from there: do not repeat steps that are already marked as completed.
            """;

    private final SystemPromptAssembler systemPromptAssembler;
    private final String defaultSystemPrompt;
    private final ContextAttachmentProvider contextAttachmentProvider;
    private final ConversationTranscriptSink conversationTranscriptSink;

    public DefaultConversationManager(SystemPromptAssembler systemPromptAssembler, String defaultSystemPrompt,
                                      ContextAttachmentProvider contextAttachmentProvider) {
        this(systemPromptAssembler, defaultSystemPrompt, null, contextAttachmentProvider);
    }

    public DefaultConversationManager(SystemPromptAssembler systemPromptAssembler, String defaultSystemPrompt,
                                      ConversationTranscriptSink conversationTranscriptSink,
                                      ContextAttachmentProvider contextAttachmentProvider) {
        this.systemPromptAssembler = systemPromptAssembler;
        this.defaultSystemPrompt = defaultSystemPrompt;
        this.contextAttachmentProvider = contextAttachmentProvider == null
                ? ContextAttachmentProvider.NONE : contextAttachmentProvider;
        this.conversationTranscriptSink = conversationTranscriptSink;
    }

    @Override
    public void startConversation(Execution execution, Workspace workspace) {
        if (execution.getMessages() == null) execution.setMessages(new ArrayList<>());
        if (execution.getTokenUsage() == null) execution.setTokenUsage(TokenUsageEntity.empty());
        setLeadingSystemMessage(execution, buildSystemMessage(execution.getAgentRequest(), workspace));
    }

    @Override
    public void addMessage(Execution execution, ChatResponseEntity response,
                           @Nullable List<ToolExecuteResult> toolResults) {
        List<Message> messages = execution.getMessages();

        AiMessageEntity aiMessage = response.getAiMessageEntity();
        List<ToolMessageEntity> toolMessages = new ArrayList<>();

        if (toolResults != null) {
            for (ToolExecuteResult result : toolResults) {
                var definition = result.getToolSpecification();
                toolMessages.add(ToolMessageEntity.builder()
                        .id(result.getId())
                        .name(definition == null ? "unknown tool" : definition.name())
                        .text(result.getToolOutput())
                        .build());
            }
        }

        messages.add(aiMessage);

        messages.addAll(toolMessages);

        execution.setAiMessage(aiMessage);

        execution.getTokenUsage().add(response.getTokenUsage());

        if (conversationTranscriptSink != null) {
            conversationTranscriptSink.appendRound(execution.getId(), aiMessage, List.copyOf(toolMessages));
        }
    }

    @Override
    public List<Message> messages(Execution execution) {
        return Collections.unmodifiableList(execution.getMessages());
    }

    @Override
    public TokenUsageEntity tokenUsage(Execution execution) {
        return execution.getTokenUsage();
    }

    @Override
    public void appendUserMessage(Execution execution, String text) {
        if (text != null && !text.isBlank()) execution.getMessages().add(UserMessageEntity.from(text));
    }

    @Override
    public void appendSystemMessage(Execution execution, String text) {
        if (text != null && !text.isBlank()) {
            execution.getMessages().add(SystemMessageEntity.builder().text(text).build());
        }
    }

    @Override
    public void refreshToolSet(Execution execution, String customSystemPrompt, String task,
                               List<String> toolList, Workspace workspace) {
        setLeadingSystemMessage(execution, SystemMessageEntity.builder()
                .text(assembleSystemPrompt(workspace, customSystemPrompt, task, toolList)).build());
    }

    @Override
    public void rebuildContext(ContextSummary summary, Execution execution) {
        rebuildContext(summary, execution, false);
    }

    @Override
    public void rebuildContext(ContextSummary summary, Execution execution, boolean answeredTrailingUserTurn) {
        if (summary == null) return;
        List<Message> current = execution.getMessages();
        SystemMessageEntity leading = current.stream()
                .filter(SystemMessageEntity.class::isInstance)
                .map(SystemMessageEntity.class::cast)
                .findFirst()
                .orElse(SystemMessageEntity.builder().text(defaultSystemPrompt).build());
        List<Message> tail = retainableTail(current, answeredTrailingUserTurn);
        List<Message> rebuilt = new ArrayList<>();
        rebuilt.add(leading);
        contextAttachmentProvider.attachment(execution.getId())
                .filter(value -> !value.isBlank())
                .ifPresent(value -> rebuilt.add(SystemMessageEntity.builder().text(String.format("""
                        The protected application state produced earlier is reproduced below. Keep following it:
                        
                        %s
                        """, value)).build()));
        rebuilt.add(SystemMessageEntity.builder().text(String.format("""
                        The compact_context tool has been executed successfully, and the conversation history has been compressed into the following summary:
                        goal:
                        %s
                        summary:
                        %s
                        completed task:
                        %s
                        pending task:
                        %s
                        summary-task state:
                        %s
                        Continue the conversation based on this summary. Do NOT execute anything about this summary
                        """, summary.getGoal(), summary.getSummary(), summary.getCompleted(), summary.getPending(),
                summary.getState())).build());
        if (tail.isEmpty()) rebuilt.add(UserMessageEntity.from(CONTINUE_AFTER_COMPACTION_PROMPT));
        else rebuilt.addAll(tail);
        execution.setMessages(rebuilt);
    }

    private SystemMessageEntity buildSystemMessage(AgentRequest request, Workspace workspace) {
        return SystemMessageEntity.builder().text(assembleSystemPrompt(workspace, request.getSystemPrompt(),
                request.getTask(), request.getToolList())).build();
    }

    private String assembleSystemPrompt(Workspace workspace, String customPrompt, String task,
                                        List<String> tools) {
        return systemPromptAssembler.assemble(defaultSystemPrompt, workspace, customPrompt, task, tools);
    }

    private void setLeadingSystemMessage(Execution execution, SystemMessageEntity systemMessage) {
        List<Message> messages = execution.getMessages();
        if (!messages.isEmpty() && messages.getFirst() instanceof SystemMessageEntity) messages.set(0, systemMessage);
        else messages.addFirst(systemMessage);
    }

    private List<Message> retainableTail(List<Message> messages, boolean answeredTrailingUserTurn) {
        List<Message> result = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message instanceof ToolMessageEntity) {
                result.addFirst(message);
                continue;
            }
            if (message instanceof AiMessageEntity || message instanceof UserMessageEntity) {
                result.addFirst(message);
                break;
            }
        }
        if (answeredTrailingUserTurn && result.stream().anyMatch(UserMessageEntity.class::isInstance)) return List.of();
        boolean hasAssistant = result.stream().anyMatch(AiMessageEntity.class::isInstance);
        if (!hasAssistant) return result.stream().noneMatch(ToolMessageEntity.class::isInstance) ? result : List.of();
        return result.stream().allMatch(DefaultConversationManager::isThinkingSafe) ? result : List.of();
    }

    private static boolean isThinkingSafe(Message message) {
        if (!(message instanceof AiMessageEntity ai)) return true;
        return ai.getThinking() != null && !ai.getThinking().isBlank();
    }
}
