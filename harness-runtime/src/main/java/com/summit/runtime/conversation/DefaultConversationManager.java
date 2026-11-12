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
import com.summit.runtime.prompt.SystemPromptAssembler;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import java.util.*;
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

    /**
     * Fraction of the context cap the trailing turn may occupy before {@link #retainableTail} shrinks
     * it down to its last assistant message. Kept as a named policy constant instead of a literal
     * buried in the method; a full Spring-configurable binding is tracked separately.
     */
    private static final double TAIL_COMPACT_THRESHOLD = 0.20;

    private final Tokenizer tokenizer;
    private final int maxTokens;
    /**
     * Fallback context cap for the convenience constructors; the Spring bean passes the real one.
     */
    private static final int DEFAULT_MAX_TOKENS = 1_024_000;
    /**
     * How many live per-round context blocks may sit in an uncompacted history before the manager
     * starts complaining. See {@link #warnWhenContextPilesUp}.
     */
    private static final int DEFAULT_MAX_LIVE_CONTEXT_BLOCKS = 4;
    private final int maxLiveContextBlocks;
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

    /** Convenience entry point for the common case: no transcript sink, default tokenizer. */
    public DefaultConversationManager(ContextAttachmentProvider contextAttachmentProvider) {
        this(null, 0, 0, contextAttachmentProvider, null, null, null);
    }

    /** Convenience entry point for callers that only swap the tokenizer. */
    public DefaultConversationManager(ContextAttachmentProvider contextAttachmentProvider, Tokenizer tokenizer) {
        this(tokenizer, 0, 0, contextAttachmentProvider, null, null, null);
    }

    /**
     * Collects the collaborators this manager needs, replacing six overloads whose argument order
     * was the only thing telling "the sink" from "the tokenizer" apart.
     *
     * <p>Every parameter is optional: a non-positive number or a {@code null} reference falls back
     * to the production default, so a caller states only what it wants to change.</p>
     */
    @Builder
    private DefaultConversationManager(Tokenizer tokenizer,
                                       int maxTokens,
                                       int maxLiveContextBlocks,
                                       ContextAttachmentProvider contextAttachmentProvider,
                                       ConversationTranscriptSink conversationTranscriptSink,
                                       Supplier<PromptAssembler> promptAssemblerFactory,
                                       SkillLoader skillLoader) {
        this.tokenizer = tokenizer == null ? new DefaultTokenizer() : tokenizer;
        this.maxTokens = maxTokens > 0 ? maxTokens : DEFAULT_MAX_TOKENS;
        this.maxLiveContextBlocks = maxLiveContextBlocks > 0
                ? maxLiveContextBlocks : DEFAULT_MAX_LIVE_CONTEXT_BLOCKS;

        this.contextAttachmentProvider = contextAttachmentProvider == null
                ? ContextAttachmentProvider.NONE : contextAttachmentProvider;
        this.conversationTranscriptSink = conversationTranscriptSink;
        this.promptAssemblerFactory = promptAssemblerFactory == null
                ? SystemPromptAssembler::new : promptAssemblerFactory;
        this.skillLoader = skillLoader;
    }


    @Override
    public void startConversation(Execution execution, Workspace workspace, McpToolScope mcpToolScope) {
        AgentRequest agentRequest = execution.getAgentRequest();
        McpToolScope scope = mcpToolScope == null ? McpToolScope.EMPTY : mcpToolScope;

        List<Message> current = execution.getMessages() == null ? new ArrayList<>() : execution.getMessages();
        List<Message> rebuilt = new ArrayList<>(current);
        boolean changed = false;

        // A session with no assistant turn yet is a fresh one: it gets the (cacheable) system
        // prompt, and its accounting starts from zero.
        if (rebuilt.stream().noneMatch(AiMessageEntity.class::isInstance)) {
            execution.setTokenUsage(TokenUsageEntity.empty());
            if (rebuilt.isEmpty() || !(rebuilt.getFirst() instanceof SystemMessageEntity)) {
                rebuilt.addFirst(buildSystemMessage(agentRequest, workspace));
                changed = true;
            }
        }
        if (execution.getTokenUsage() == null) {
            execution.setTokenUsage(TokenUsageEntity.empty());
        }

        // Everything that varies per round goes behind the cached prefix, right before the user
        // turn. When it has not changed, history is left untouched so the prefix keeps hitting.
        ContextChangeDiscover.Layout layout = ContextChangeDiscover.layout(
                rebuilt, agentRequest, scope, loadSkill(agentRequest.getSkillConfig()));
        if (layout.insert()) {
            rebuilt.add(layout.insertIndex(), layout.contract());
            changed = true;
        }

        warnWhenContextPilesUp(execution, rebuilt);

        // Replacing the list is what tells the incremental token baseline that its snapshot is
        // stale; leaving the reference alone when nothing changed keeps that baseline valid.
        if (changed) {
            execution.setMessages(rebuilt);
        }
    }

    @Override
    public void addMessage(LoopMessages loopMessages) {
        Execution execution = loopMessages.getExecution();

        ChatResponseEntity response = loopMessages.getResponse();

        final List<Message> messages = execution.getMessages();

        AiMessageEntity aiMessage = response.getAiMessageEntity();


        messages.add(aiMessage);

        List<ToolMessageEntity> convertedMessages = loopMessages.convert();
        messages.addAll(convertedMessages);

        execution.setAiMessage(aiMessage);

        if (conversationTranscriptSink != null) {
            conversationTranscriptSink.appendRound(execution.getId(), aiMessage, List.copyOf(convertedMessages),
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
    public void rebuildContext(String summary, Execution execution) {
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

        contextAttachmentProvider.attachment(execution.getId())
                .filter(value -> !value.isBlank())
                .ifPresent(value -> attachment.add(SystemMessageEntity.builder().text(String.format("""
                        The protected application state produced earlier is reproduced below. Keep following it:
                        
                        %s
                        """, value)).build()));

        List<Message> retained = retainCompactionInputs(current);
        List<Message> tail = retainableTail(retained);
        List<Message> earlierUsers = retained.subList(0, retained.size() - tail.size()).stream()
                .filter(UserMessageEntity.class::isInstance).toList();

        rebuilt.add(leading);
        rebuilt.addAll(attachment);
        rebuilt.add(summaryMessage);
        rebuilt.addAll(earlierUsers);
        rebuilt.addAll(stripOldThinking(tail));


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
     * <p>The prompt itself is not composed here. It arrives as {@code AgentRequest.contextSpec.systemPrompt} and
     * is rendered verbatim: the framework holds no default of its own, so there is exactly one place
     * that decides what the model is told about its role.</p>
     *
     * <p>The MCP section carries résumés only. The request's remote tools are never declared in the
     * model's tool list up front; they are published here as name and description and unlocked one
     * at a time through {@code search_tool}. See {@link McpToolScope}.</p>
     */
    private SystemMessageEntity buildSystemMessage(AgentRequest request, Workspace workspace) {
        Objects.requireNonNull(request);

        // Stable half only: environment + the application's own prompt. Everything that varies per
        // round is rendered by ContextDynamicAssembler, so this message stays byte-identical.
        return promptAssemblerFactory.get()
                .startWithWorkspace(workspace)
                .withBusinessPrompt(businessPromptOf(request))
                .complete();
    }

    /**
     * Reports an uncompacted history that keeps growing context blocks.
     *
     * <p>Appending is what protects the cache, so this never rewrites history on its own. If the
     * count keeps climbing, compaction is not running often enough for the amount of dynamic
     * content this agent produces — the two knobs are {@code compactionThreshold} (trigger earlier)
     * and the pruner, both owned by the compaction strategy, not by this manager.</p>
     *
     * <p>It warns when the count crosses the limit and again on every further multiple, so a long
     * uncompacted session logs a handful of lines instead of one per round.</p>
     */
    private void warnWhenContextPilesUp(Execution execution, List<Message> messages) {
        int live = 0;
        for (Message message : messages) {
            if (message instanceof UserMessageEntity user && user.hasContextContract()) live++;
        }
        boolean crossed = live > maxLiveContextBlocks
                && (live == maxLiveContextBlocks + 1 || live % maxLiveContextBlocks == 0);
        if (crossed) {
            log.warn("Uncompacted history holds {} dynamic context blocks (limit {}): the per-round "
                            + "context is piling up. Lower compactionThreshold or enable the context "
                            + "pruner. executionId: {}",
                    live, maxLiveContextBlocks, execution.getId());
        }
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
        String prompt = request == null ? null : request.getContextSpec().getSystemPrompt();
        return prompt == null ? "" : prompt.strip();
    }


    private List<Message> retainCompactionInputs(List<Message> current) {
        List<Message> retained = new ArrayList<>(current.size());
        boolean hasLaterAssistantReply = false;
        boolean keptDynamicContext = false;
        // Use the original order so a prior reply cannot confirm a newly injected input.
        for (Message message : current.reversed()) {
            if (message instanceof AiMessageEntity) hasLaterAssistantReply = true;
            if (message instanceof UserMessageEntity compactionCandidate
                    && compactionCandidate.hasContextContract()) {
                // Dynamic context is rebuilt every round, so one copy is enough: reverse order meets
                // the newest first, and the older ones are dropped instead of piling up per turn.
                if (!keptDynamicContext) {
                    keptDynamicContext = true;
                    retained.add(compactionCandidate);
                }
                continue;
            }
            Message candidate = message instanceof UserMessageEntity user
                    ? user.retainForCompaction(hasLaterAssistantReply) : message;
            if (candidate != null) retained.add(candidate);
        }
        return retained.reversed();
    }

    private List<Message> retainableTail(List<Message> messages) {
        int lastUserMessageIndex = -1;


        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessageEntity user && !user.hasCompactionMarker()) {
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
        double ratio = tokenizer.calcCurrentTokenRatio(result, List.of(), maxTokens);

        if (ratio >= TAIL_COMPACT_THRESHOLD) {
            return furtherCompact(result);
        }

        return result;
    }

    private List<Message> furtherCompact(ArrayList<Message> result) {
        int lastAiMessageIndex = -1;

        for (int i = result.size() - 1; i >= 0; i--) {
            Message message = result.get(i);
            if (message instanceof AiMessageEntity aiMessageEntity) {
                lastAiMessageIndex = i;
                break;
            }
        }

        if (lastAiMessageIndex == -1) {
            return List.of();
        }

        return new ArrayList<>(
                result.subList(lastAiMessageIndex, result.size())
        );
    }

    /**
     * Removes thinking (and its provider signature attribute) from the retained trailing assistant
     * messages.
     *
     * <p>A summary rebuild replaces the history prefix, which invalidates any thinking signature the
     * trailing assistant turn still carries: replaying that signature under a changed prefix is what
     * a prefix-bound preserved-thinking model rejects (signature mismatch). Only this rebuild path
     * strips thinking; a plain snapshot restore keeps it, because only here does the prefix change.</p>
     */
    private static List<Message> stripOldThinking(List<Message> tail) {
        List<Message> stripped = new ArrayList<>(tail.size());
        for (Message message : tail) {
            if (message instanceof AiMessageEntity ai) {
                boolean thinkingFree = (ai.getThinking() == null || ai.getThinking().isBlank())
                        && (ai.getAttributes() == null || ai.getAttributes().isEmpty());
                stripped.add(thinkingFree ? ai : AiMessageEntity.builder()
                        .text(ai.getText())
                        .thinking(null)
                        .attributes(java.util.Map.of())
                        .toolCalls(ai.getToolCalls() == null ? List.of() : List.copyOf(ai.getToolCalls()))
                        .build());
            } else {
                stripped.add(message);
            }
        }
        return stripped;
    }

    private String processSummary(String summary) {
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
