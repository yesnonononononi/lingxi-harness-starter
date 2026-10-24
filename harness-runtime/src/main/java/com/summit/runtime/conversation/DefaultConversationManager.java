package com.summit.runtime.conversation;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.compact.ContextAttachmentProvider;
import com.summit.core.compact.Tokenizer;
import com.summit.core.conf.SkillConfig;
import com.summit.core.conversation.ConversationManager;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.api.ConversationTranscriptSink;
import com.summit.core.conversation.message.*;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.prompt.PromptAssembler;
import com.summit.core.runtime.loop.LoopMessages;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.skill.SkillLoader;
import com.summit.core.skill.SkillResume;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.runtime.prompt.SystemPromptAssembler;
import lombok.extern.slf4j.Slf4j;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;


/**
 * Stateless operations over the context owned by one execution.
 */
@Slf4j
public class DefaultConversationManager implements ConversationManager {
    private static final String CONTINUE_AFTER_COMPACTION_PROMPT = """
            The conversation history before this point has been compacted into the summary above.
            you may can see all user messages of the history, after that, lastest turn message
            Continue the work from there: do not repeat steps that are already marked as completed.
            """;

    private final Tokenizer tokenizer;
    private final int maxTokens;
    /** Fallback context cap for the convenience constructors; the Spring bean passes the real one. */
    private static final int DEFAULT_MAX_TOKENS = 1_024_000;
    private final ContextAttachmentProvider contextAttachmentProvider;
    private final ConversationTranscriptSink conversationTranscriptSink;
    private final SkillLoader skillLoader;
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
        this(contextAttachmentProvider, new DefaultTokenizer());
    }

    public DefaultConversationManager(ContextAttachmentProvider contextAttachmentProvider, Tokenizer tokenizer) {
        this(null, contextAttachmentProvider, tokenizer);
    }

    public DefaultConversationManager(ConversationTranscriptSink conversationTranscriptSink,
                                      ContextAttachmentProvider contextAttachmentProvider) {
        this(new DefaultTokenizer(), DEFAULT_MAX_TOKENS, conversationTranscriptSink, contextAttachmentProvider,
                SystemPromptAssembler::new, null);
    }

    public DefaultConversationManager(ConversationTranscriptSink conversationTranscriptSink,
                                      ContextAttachmentProvider contextAttachmentProvider,
                                      Supplier<PromptAssembler> promptAssemblerFactory,
                                      SkillLoader skillLoader) {
        this(new DefaultTokenizer(), DEFAULT_MAX_TOKENS, conversationTranscriptSink, contextAttachmentProvider,
                promptAssemblerFactory, skillLoader);
    }

    public DefaultConversationManager(Tokenizer tokenizer, ConversationTranscriptSink conversationTranscriptSink,
                                      ContextAttachmentProvider contextAttachmentProvider,
                                      Supplier<PromptAssembler> promptAssemblerFactory,
                                      SkillLoader skillLoader) {
        this(tokenizer, DEFAULT_MAX_TOKENS, conversationTranscriptSink, contextAttachmentProvider,
                promptAssemblerFactory, skillLoader);
    }

    public DefaultConversationManager(ConversationTranscriptSink conversationTranscriptSink,
                                      ContextAttachmentProvider contextAttachmentProvider, Tokenizer tokenizer
    ) {
        this(tokenizer, DEFAULT_MAX_TOKENS, conversationTranscriptSink, contextAttachmentProvider, SystemPromptAssembler::new, null);
    }

    public DefaultConversationManager(Tokenizer tokenizer, int maxTokens,
                                      ConversationTranscriptSink conversationTranscriptSink,
                                      ContextAttachmentProvider contextAttachmentProvider,
                                      Supplier<PromptAssembler> promptAssemblerFactory,
                                      SkillLoader skillLoader
    ) {
        this.tokenizer = tokenizer;
        this.maxTokens = maxTokens > 0 ? maxTokens : DEFAULT_MAX_TOKENS;

        this.contextAttachmentProvider = contextAttachmentProvider == null
                ? ContextAttachmentProvider.NONE : contextAttachmentProvider;
        this.conversationTranscriptSink = conversationTranscriptSink;
        this.promptAssemblerFactory = promptAssemblerFactory == null
                ? SystemPromptAssembler::new : promptAssemblerFactory;
        this.skillLoader = skillLoader;
    }

    @Override
    public void startConversation(Execution execution, Workspace workspace, McpToolScope mcpToolScope) {
        if (execution.getMessages() == null) execution.setMessages(new ArrayList<>());
        if (execution.getTokenUsage() == null) execution.setTokenUsage(TokenUsageEntity.empty());
        setLeadingSystemMessage(execution,
                buildSystemMessage(execution.getAgentRequest(), workspace, mcpToolScope));
    }

    @Override
    public void addMessage(LoopMessages loopMessages) {
        Execution execution = loopMessages.getExecution();
        ChatResponseEntity response = loopMessages.getResponse();
        List<Message> messages = execution.getMessages();

        AiMessageEntity aiMessage = response.getAiMessageEntity();
        List<ToolMessageEntity> toolMessages = new ArrayList<>();

        if (loopMessages.getToolExecuteResults() != null) {
            for (ToolExecuteResult result : loopMessages.getToolExecuteResults()) {
                ToolDefinition<?> definition = result.getToolSpecification();
                toolMessages.add(ToolMessageEntity.builder()
                        .id(result.getId())
                        .name(definition == null ? "unknown tool" : definition.name())
                        .toolOutput(result.getToolOutput())
                        .isError(!result.isSuccess())
                        // The tool's own exit status travels with its message so a pruned skeleton can
                        // still tell a failed command from a successful one.
                        .exitCode(result.getExitCode())
                        .build());
            }
        }

        messages.add(aiMessage);

        messages.addAll(toolMessages);

        execution.setAiMessage(aiMessage);

        if (conversationTranscriptSink != null) {
            conversationTranscriptSink.appendRound(execution.getId(), aiMessage, List.copyOf(toolMessages),
                    response.getResponseId(), execution.eventMetaData());
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
    public void rebuildContext(String summary, Execution execution, boolean answeredTrailingUserTurn) {
        if (summary == null || summary.isBlank()) return;

        List<Message> current = execution.getMessages();
        List<Message> attachment = new ArrayList<>();
        SystemMessageEntity summaryMessage = SystemMessageEntity.builder().text(processSummary(summary)).build();
        List<Message> rebuilt = new ArrayList<>();

        // protection policy ONE :  find the original system prompt (the protection policy)
        SystemMessageEntity leading = current.stream()
                .filter(SystemMessageEntity.class::isInstance)
                .map(SystemMessageEntity.class::cast)
                .findFirst()
                .orElseGet(() -> SystemMessageEntity.builder().text(businessPromptOf(execution)).build());

        List<Message> userMessageList = execution.getMessages().stream().filter(UserMessageEntity.class::isInstance).toList();

        contextAttachmentProvider.attachment(execution.getId())
                .filter(value -> !value.isBlank())
                .ifPresent(value -> attachment.add(SystemMessageEntity.builder().text(String.format("""
                        The protected application state produced earlier is reproduced below. Keep following it:
                      
                        %s
                        """, value)).build()));

        // protection policy TWO :  find the last user message and the turn belong to it
        List<Message> tail = retainableTail(current);

        // leading -- attachment -- summary(processed) -- userMessage list -- last turns
        rebuilt.add(leading);
        rebuilt.addAll(attachment);
        rebuilt.add(summaryMessage);
        rebuilt.addAll(userMessageList);
        rebuilt.addAll(tail);


        execution.setMessages(rebuilt);

        // Only the counters this class owns are written (§3.3.4): a rebuild advances the summary
        // generation and invalidates any prune, but says nothing about the attempt budget or the
        // "summarization is closed" latch — resetting those here is the P0-1 regression.
        execution.setCompactionState(execution.getCompactionState().afterSummaryRebuild());
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
        Objects.requireNonNull(request);

        McpToolScope scope = mcpToolScope == null ? McpToolScope.EMPTY : mcpToolScope;

        List<SkillResume> skillResumes = loadSkill(request.getSkillConfig());

        String result = promptAssemblerFactory.get()
                .startWithWorkspace(workspace)
                .withBusinessPrompt(businessPromptOf(request))
                .withMcpToolPrompt(scope.resumes())
                .withTaskPrompt(request.getTask())
                .withSkillPrompt(skillResumes)
                .complete();

        return SystemMessageEntity.builder()
                .text(result)
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

    private List<Message> retainableTail(List<Message> messages) {
        int lastUserMessageIndex = -1;
        final double THRESHOLD = 0.20;

        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessageEntity) {
                lastUserMessageIndex = i;
                break;
            }
        }

        if (lastUserMessageIndex == -1) {
            return List.of();
        }

        if (lastUserMessageIndex == messages.size() - 1) {
            return List.of();
        }

        ArrayList<Message> result = new ArrayList<>(
                messages.subList(lastUserMessageIndex + 1, messages.size())
        );

        // tokenizer needs to calc the result tokens, if it exceeds the acceptable limit, then the policy further needs to compact
        double ratio = tokenizer.calcCurrentTokenRatio(result, maxTokens);

        if(ratio >= THRESHOLD){
            return furtherCompact(result);
        }

        return result;
    }

    private List<Message> furtherCompact(ArrayList<Message> result) {
        int lastAiMessageIndex = -1;

        for (int i = result.size() - 1; i >= 0; i--) {
            Message message = result.get(i);
            if(message instanceof AiMessageEntity aiMessageEntity){
                lastAiMessageIndex = i;
                break;
            }
        }

        if(lastAiMessageIndex == -1) {
            return List.of();
        }

        return new ArrayList<>(
                result.subList(lastAiMessageIndex, result.size())
        );
    }

    private String processSummary(String summary){
        return summary + "\n" + CONTINUE_AFTER_COMPACTION_PROMPT;
    }


    private List<SkillResume> loadSkill(SkillConfig skillConfig) {
        try {
            if (skillConfig == null) return List.of();
            return skillLoader.load(skillConfig);
        } catch (Exception e) {
            log.error("Failed to load skill", e);
            throw e;
        }
    }
}
