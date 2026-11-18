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
    private final ContextRebuilder contextRebuilder;
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
        ContextAttachmentProvider attachmentProvider =
                contextAttachmentProvider == null ? ContextAttachmentProvider.NONE : contextAttachmentProvider;
        this.contextRebuilder = new ContextRebuilder(this.tokenizer, this.maxTokens, attachmentProvider);
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

        // Taken before the save below, not at publish time: the revision has to become durable with
        // the context it numbers, or a resumed run would report the same revision twice.
        long contextRevision = execution.nextContextRevision();

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
                .contextRevision(contextRevision)
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

        List<Message> rebuilt = contextRebuilder.rebuild(summary, execution, businessPromptOf(execution));
        execution.setMessages(rebuilt);

        // Only the counters this class owns are written (§3.3.4): a rebuild advances the summary
        // generation and invalidates any prune, but says nothing about the attempt budget or the
        // "summarization is closed" latch — resetting those here is the P0-1 regression.
        execution.setCompactionState(execution.getCompactionState().afterSummaryRebuild());

        // Same rule as startConversation, with the same ordering: the rebuilt list is in place and
        // saved before the rebuild is reported, so a consumer reading it back sees the session it
        // describes — in memory and in the store.
        long contextRevision = execution.nextContextRevision();

        checkpoint.save(execution);

        contextEventPublisher.onContextRebuilt(ContextRebuiltEvent.builder()
                .executionId(execution.getId())
                .metaData(execution.eventMetaData())
                .contextRevision(contextRevision)
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
