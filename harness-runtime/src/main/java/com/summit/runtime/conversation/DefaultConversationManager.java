package com.summit.runtime.conversation;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.compact.ContextAttachmentProvider;
import com.summit.core.compact.ContextSummary;
import com.summit.core.conversation.ConversationManager;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.api.ConversationTranscriptSink;
import com.summit.core.conversation.message.*;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.prompt.PromptAssembler;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.runtime.prompt.SystemPromptAssembler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Stateless operations over the context owned by one execution.
 */
public class DefaultConversationManager implements ConversationManager {
    private static final String CONTINUE_AFTER_COMPACTION_PROMPT = """
            The conversation history before this point has been compacted into the summary above.
            Continue the work from there: do not repeat steps that are already marked as completed.
            """;


    private final ContextAttachmentProvider contextAttachmentProvider;
    private final ConversationTranscriptSink conversationTranscriptSink;
    /**
     * Factory of the assembler that renders each execution's leading system message.
     *
     * <p>A supplier rather than a shared instance because the assembler is stateful during one
     * assembly — it accumulates sections in a builder before {@code complete()}. Handing out a fresh
     * one per execution keeps concurrent runs from sharing that scratch state, while still letting
     * the application swap in its own {@link PromptAssembler} implementation.</p>
     */
    private final Supplier<PromptAssembler> promptAssemblerFactory;


    public DefaultConversationManager(ContextAttachmentProvider contextAttachmentProvider) {
        this(null, contextAttachmentProvider);
    }

    public DefaultConversationManager(ConversationTranscriptSink conversationTranscriptSink,
                                      ContextAttachmentProvider contextAttachmentProvider) {
        this(conversationTranscriptSink, contextAttachmentProvider, SystemPromptAssembler::new);
    }

    public DefaultConversationManager(ConversationTranscriptSink conversationTranscriptSink,
                                      ContextAttachmentProvider contextAttachmentProvider,
                                      Supplier<PromptAssembler> promptAssemblerFactory) {

        this.contextAttachmentProvider = contextAttachmentProvider == null
                ? ContextAttachmentProvider.NONE : contextAttachmentProvider;
        this.conversationTranscriptSink = conversationTranscriptSink;
        this.promptAssemblerFactory = promptAssemblerFactory == null
                ? SystemPromptAssembler::new : promptAssemblerFactory;
    }

    @Override
    public void startConversation(Execution execution, Workspace workspace, McpToolScope mcpToolScope) {
        if (execution.getMessages() == null) execution.setMessages(new ArrayList<>());
        if (execution.getTokenUsage() == null) execution.setTokenUsage(TokenUsageEntity.empty());
        setLeadingSystemMessage(execution,
                buildSystemMessage(execution.getAgentRequest(), workspace, mcpToolScope));
    }

    @Override
    public void addMessage(Execution execution, ChatResponseEntity response,
                            List<ToolExecuteResult> toolResults) {
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
            conversationTranscriptSink.appendRound(execution.getId(), aiMessage, List.copyOf(toolMessages), execution.eventMetaData());
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
    public void appendSystemMessage(Execution execution, SystemMessageEntity entity) {
        if (entity.getText() != null && !entity.getText().isBlank()) {
            execution.getMessages().add(entity);
        }
    }

    @Override
    public void appendMessage(Execution execution, Message e) {
        switch (e) {
            case SystemMessageEntity entity -> execution.getMessages().add(entity);
            case UserMessageEntity entity -> execution.getMessages().add(entity);
            case AiMessageEntity entity -> execution.getMessages().add(entity);
            case ToolMessageEntity entity -> execution.getMessages().add(entity);
            case null, default -> throw new IllegalArgumentException("Unknown message type: " + e);
        }
    }


    @Override
    public void rebuildContext(ContextSummary summary, Execution execution, boolean answeredTrailingUserTurn) {
        if (summary == null) return;
        List<Message> current = execution.getMessages();
        SystemMessageEntity leading = current.stream()
                .filter(SystemMessageEntity.class::isInstance)
                .map(SystemMessageEntity.class::cast)
                .findFirst()
                .orElseGet(() -> SystemMessageEntity.builder().text(businessPromptOf(execution)).build());
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

    /**
     * Builds the leading system message: where the agent runs, who it is, the remote tools it may
     * look up, and — last, closest to the user's turn — the task this execution was delegated.
     *
     * <p>The prompt itself is not composed here. It arrives as {@code AgentRequest.systemPrompt} and
     * is rendered verbatim: the framework holds no default of its own, so there is exactly one place
     * that decides what the model is told about its role.</p>
     *
     * <p>The MCP section carries résumés only. The request's remote tools are never declared in the
     * model's tool list up front; they are published here as name and description and unlocked one
     * at a time through {@code search_tool}. See {@link McpToolScope}.</p>
     */
    private SystemMessageEntity buildSystemMessage(AgentRequest request, Workspace workspace,
                                                  McpToolScope mcpToolScope) {
        McpToolScope scope = mcpToolScope == null ? McpToolScope.EMPTY : mcpToolScope;

        String result = promptAssemblerFactory.get()
                .startWithWorkspace(workspace)
                .withBusinessPrompt(businessPromptOf(request))
                .withMcpToolPrompt(scope.resumes())
                .withTaskPrompt(request == null ? null : request.getTask())
                .complete();

        return SystemMessageEntity.builder()
                .text(result )
                .build();
    }

    /**
     * The prompt of this execution, taken from the request and nowhere else.
     *
     * <p>Empty when the application supplied none. Substituting a framework sentence here would put
     * text in front of the model that nobody wrote, and it would do so invisibly — the application
     * would have no way to tell its own prompt from the framework's.</p>
     */
    private static String businessPromptOf(Execution execution) {
        AgentRequest request = execution == null ? null : execution.getAgentRequest();
        return businessPromptOf(request);
    }

    private static String businessPromptOf(AgentRequest request) {
        String prompt = request == null ? null : request.getSystemPrompt();
        return prompt == null ? "" : prompt.strip();
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
