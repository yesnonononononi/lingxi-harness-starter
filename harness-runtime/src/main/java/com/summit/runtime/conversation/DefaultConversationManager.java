package com.summit.runtime.conversation;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.compact.ContextAttachmentProvider;
import com.summit.core.compact.Tokenizer;
import com.summit.core.conf.SkillConfig;
import com.summit.core.conversation.ChangedDiff;
import com.summit.core.conversation.CheckpointSaver;
import com.summit.core.conversation.ConversationManager;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.api.ConversationTranscriptSink;
import com.summit.core.conversation.event.ContextChangedEvent;
import com.summit.core.conversation.event.ContextChangeReason;
import com.summit.core.conversation.event.ContextEventPublisher;
import com.summit.core.conversation.event.ContextRebuiltEvent;
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
import java.util.stream.Collectors;


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
    private static final int PREFIX_CHANGED_PARTIAL_TEXT_MAX_LENGTH = 100;

    /**
     * Fallback context cap for the convenience constructors; the Spring bean passes the real one.
     */
    private static final int DEFAULT_MAX_TOKENS = 1_024_000;
    /**
     * How many live per-round context blocks may sit in an uncompacted history before the manager
     * starts complaining.
     */
    private static final int DEFAULT_MAX_LIVE_CONTEXT_BLOCKS = 4;
    /**
     * Shared by every manager built without listeners: the manager still reports, into nothing.
     *
     * <p>Not {@code null}: "nobody is listening" is the normal case for a manager a test or a
     * single-purpose caller assembled, and it must not turn the run's own notification path into an
     * NPE.</p>
     */
    private static final ContextEventPublisher EMPTY_CONTEXT_EVENTS = new ContextEventPublisher(List.of());
    /**
     * Factory of the assembler that renders each execution's leading system message.
     *
     * <p>A supplier rather than a shared instance because the assembler is stateful during one
     * assembly — it accumulates sections in a builder before {@code complete()}. Handing out a fresh
     * one per execution keeps concurrent runs from sharing that scratch state, while still letting
     * the application swap in its own {@link PromptAssembler} implementation.</p>
     */
    private final Supplier<PromptAssembler> promptAssemblerFactory;

    private final Tokenizer tokenizer;
    private final int maxTokens;
    private final int maxLiveContextBlocks;
    private final ContextAttachmentProvider contextAttachmentProvider;
    private final ConversationTranscriptSink conversationTranscriptSink;
    private final SkillLoader skillLoader;

    /**
     * The context surface this manager reports its own changes on.
     *
     * <p>Named after the channel, not after a listener list: the manager states the fact, and who
     * the fact is interesting to is the application's wiring decision. Never {@code null} — a
     * manager assembled without one reports into the shared empty surface, because "nobody is
     * listening" must not turn into an NPE on the run's own notification path.</p>
     *
     * <p><b>What a listener can expect to read back.</b> The fact is published inside the call that
     * made it, after the execution's message list is final, so a listener that reads
     * {@code execution.getMessages()} sees exactly the state the event describes. It is also
     * published only after the caller's {@link CheckpointSaver} has stored that state, so the event
     * can be treated as an authoritative fact rather than a hint that something might still be rolled
     * back — that ordering is why the save action arrives as a parameter instead of this class
     * owning a repository it has no business owning.</p>
     */
    private final ContextEventPublisher contextEventPublisher;

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
                                       SkillLoader skillLoader,
                                       ContextEventPublisher contextEventPublisher
    ) {
        this.maxTokens = maxTokens > 0 ? maxTokens : DEFAULT_MAX_TOKENS;
        this.maxLiveContextBlocks = maxLiveContextBlocks > 0 ? maxLiveContextBlocks : DEFAULT_MAX_LIVE_CONTEXT_BLOCKS;
        this.tokenizer = tokenizer == null ? new DefaultTokenizer() : tokenizer;
        this.conversationTranscriptSink = conversationTranscriptSink;
        // "Every parameter is optional" has to hold for these two too: a caller that only wants the
        // defaults must not get an NPE on the first startConversation.
        this.promptAssemblerFactory =
                promptAssemblerFactory == null ? SystemPromptAssembler::new : promptAssemblerFactory;
        this.contextAttachmentProvider = contextAttachmentProvider;
        this.skillLoader = skillLoader;
        this.contextEventPublisher = contextEventPublisher == null ? EMPTY_CONTEXT_EVENTS : contextEventPublisher;

    }


    @Override
    public void startConversation(Execution execution, Workspace workspace, McpToolScope mcpToolScope,
                                  CheckpointSaver checkpoint) {
        AgentRequest agentRequest = execution.getAgentRequest();
        McpToolScope scope = mcpToolScope == null ? McpToolScope.EMPTY : mcpToolScope;

        List<Message> current = execution.getMessages() == null ? new ArrayList<>() : execution.getMessages();
        List<Message> rebuilt = new ArrayList<>(current);
        Set<ContextChangeReason> reasons = EnumSet.noneOf(ContextChangeReason.class);

        // Fresh session accounting starts from zero.
        if (rebuilt.stream().noneMatch(AiMessageEntity.class::isInstance)) {
            execution.setTokenUsage(TokenUsageEntity.empty());
        }
        if (execution.getTokenUsage() == null) {
            execution.setTokenUsage(TokenUsageEntity.empty());
        }

        // Cache protection is best-effort: when the business prompt or static workspace is unchanged,
        // keep the leading SystemMessageEntity untouched; when it changes, honestly apply it.
        SystemMessageEntity expectedSystem = buildSystemMessage(agentRequest, workspace);
        if (rebuilt.isEmpty() || !(rebuilt.getFirst() instanceof SystemMessageEntity currentSystem)) {
            rebuilt.addFirst(expectedSystem);
            reasons.add(ContextChangeReason.SYSTEM_PROMPT_INSERTED);
        } else if (!Objects.equals(currentSystem.getText(), expectedSystem.getText())) {
            rebuilt.set(0, expectedSystem);
            reasons.add(ContextChangeReason.SYSTEM_PROMPT_REPLACED);
        }

        // Everything that varies per round goes behind the cached prefix, right before the user
        // turn. When it has not changed, history is left untouched so the prefix keeps hitting.
        ContextChangeDiscover.Layout layout = ContextChangeDiscover.layout(
                rebuilt, agentRequest, scope, loadSkill(agentRequest.getSkillConfig()));

        Map<ContextSectionKind, ChangedDiff> changedInfo = layout.changedKindByLocation();

        if (layout.insert()) {
            rebuilt.add(layout.insertIndex(), layout.newContext());
            reasons.add(ContextChangeReason.DYNAMIC_SECTIONS_CHANGED);
        }

        int liveBlocks = liveContextBlocks(rebuilt);

        // Replacing the list is what tells the incremental token baseline that its snapshot is
        // stale; leaving the reference alone when nothing changed keeps that baseline valid.
        if (reasons.isEmpty()) {
            return;
        }

        execution.setMessages(rebuilt);

        warnWhenContextPilesUp(execution, liveBlocks);

        // Truncated in one pass instead of mutating the discover's map: that map may be an immutable
        // empty one, and rewriting entries in place only works as long as the implementation happens
        // to be mutable.
        Map<ContextSectionKind, ChangedDiff> truncated = changedInfo.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        entry -> new ChangedDiff(
                                diffTruncated(entry.getValue().oldContent()),
                                diffTruncated(entry.getValue().newContent()))));

        // Published only once the list the model will be given is final, and only after the caller's
        // save action has made it durable: a listener that reads the execution back must never see
        // the state from before the change it is being told about, and must never be told about a
        // session that exists only in memory.
        checkpoint.save(execution);

        contextEventPublisher.onContextChanged(ContextChangedEvent.builder()
                .executionId(execution.getId())
                .metaData(execution.eventMetaData())
                .reasons(reasons)
                .changedSections(truncated)
                .currentLiveBlocks(liveBlocks)
                .maxLiveBlocks(maxLiveContextBlocks)
                .build());
    }

    /** Live per-round context blocks in the history: one per round that appended, plus any rebuild kept. */
    private static int liveContextBlocks(List<Message> messages) {
        return (int) messages.stream()
                .filter(message -> message instanceof UserMessageEntity user && user.hasContextContract())
                .count();
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
    public void rebuildContext(String summary, Execution execution, CheckpointSaver checkpoint) {
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

        // Same rule as startConversation, with the same ordering: the rebuilt list is in place and
        // saved before the rebuild is reported, so a consumer reading it back sees the session it
        // describes — in memory and in the store.
        checkpoint.save(execution);

        contextEventPublisher.onContextRebuilt(ContextRebuiltEvent.builder()
                .executionId(execution.getId())
                .metaData(execution.eventMetaData())
                .summaryGeneration(execution.getCompactionState().summaryGeneration())
                .messageCount(rebuilt.size())
                .build());
    }


    /**
     * Truncated for transport. A null side is normal, not a bug: the "old" side of a section that
     * did not exist before is null, which is every section of a first round.
     */
    private String diffTruncated(String raw) {
        if (raw == null) return null;
        return raw.substring(0, Math.min(PREFIX_CHANGED_PARTIAL_TEXT_MAX_LENGTH, raw.length()));
    }

    /**
     * Warns when the count crosses the limit and again on every further multiple, so a long
     * uncompacted session logs a handful of lines instead of one per round.
     *
     * <p>Kept here rather than delegated to a listener: a deployment that registers no
     * {@code ContextListener} would otherwise lose the guard entirely, and this is the only place
     * that knows an append actually happened.</p>
     */
    private void warnWhenContextPilesUp(Execution execution, int live) {
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
        Map<ContextSectionKind, String> surviving = ContextDynamicAssembler.latestSections(current, current.size());
        surviving.values().removeIf(ContextDynamicAssembler.CLEARED_BODY::equals);

        List<Message> retained = new ArrayList<>(current.size());
        boolean hasLaterAssistantReply = false;
        boolean keptDynamicContext = false;
        // Use the original order so a prior reply cannot confirm a newly injected input.
        for (Message message : current.reversed()) {
            if (message instanceof AiMessageEntity) hasLaterAssistantReply = true;
            if (message instanceof UserMessageEntity compactionCandidate
                    && compactionCandidate.hasContextContract()) {
                // Dynamic context is appended incrementally by category; compaction folds the
                // latest surviving section per category into one consolidated <context> message.
                if (!keptDynamicContext) {
                    keptDynamicContext = true;
                    UserMessageEntity consolidated = consolidateDynamicContext(compactionCandidate, surviving);
                    if (consolidated != null) {
                        retained.add(consolidated);
                    }
                }
                continue;
            }
            Message candidate = message instanceof UserMessageEntity user
                    ? user.retainForCompaction(hasLaterAssistantReply) : message;
            if (candidate != null) retained.add(candidate);
        }
        return retained.reversed();
    }

    private static UserMessageEntity consolidateDynamicContext(UserMessageEntity newest,
                                                               Map<ContextSectionKind, String> surviving) {
        if (newest.contextSections().isEmpty()) {
            return newest;
        }
        if (surviving.isEmpty()) {
            return null;
        }
        if (newest.contextSections().size() == surviving.size()) {
            UserMessageEntity rebuilt = ContextDynamicAssembler.assembleSections(surviving);
            if (rebuilt != null && newest.text().equals(rebuilt.text())) {
                return newest;
            }
            return rebuilt;
        }
        return ContextDynamicAssembler.assembleSections(surviving);
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
            if (message instanceof AiMessageEntity) {
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
