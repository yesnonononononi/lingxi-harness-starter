package com.summit.runtime.conversation;

import com.summit.core.agent.AgentRequest;
import com.summit.core.compact.ContextSummary;
import com.summit.core.compact.ContextAttachmentProvider;
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
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.workspace.WorkspaceManager;
import com.summit.core.tool.LoopBoundary;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.workspace.WorkspaceSpec;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

import java.io.Serializable;
import java.util.*;



@Getter
@Slf4j
public class DefaultConversationManager implements ConversationManager {

    /** Appended when nothing of the last round survives a rebuild, so the next request still carries a user turn. */
    private static final String CONTINUE_AFTER_COMPACTION_PROMPT = """
            The conversation history before this point has been compacted into the summary above.
            Continue the work from there: do not repeat steps that are already marked as completed.
            """;

    private final ConversationStore conversationStore;
    private final RuntimeEventPublisher runtimeEventPublisher;
    private final SystemPromptAssembler systemPromptAssembler;
    private final String defaultSystemPrompt;
    /** Application state that must survive a context rebuild. */
    private final ContextAttachmentProvider contextAttachmentProvider;
    /** Resolves persisted workspace references; null only for legacy direct construction. */
    private final WorkspaceManager workspaceManager;



    public DefaultConversationManager(ConversationStore conversationStore,
                                      RuntimeEventPublisher runtimeEventPublisher,
                                      SystemPromptAssembler systemPromptAssembler,
                                      String defaultSystemPrompt,
                                      ContextAttachmentProvider contextAttachmentProvider,
                                      WorkspaceManager workspaceManager) {
        this.conversationStore = conversationStore;
        this.runtimeEventPublisher = runtimeEventPublisher;
        this.systemPromptAssembler = systemPromptAssembler;
        this.defaultSystemPrompt = defaultSystemPrompt;
        this.contextAttachmentProvider = contextAttachmentProvider == null
                ? ContextAttachmentProvider.NONE : contextAttachmentProvider;
        this.workspaceManager = workspaceManager;
    }


    @Override
    public void startConversation(AgentRequest agentRequest) {

        Optional<ConversationEntity> existing = this.conversationStore.get(agentRequest.sessionIdOrDefault());

        // if session has existed then refresh the leading system message and append input
        if (existing.isPresent()) {
            refreshSystemMessage(agentRequest, existing.get());
            appendNewUserMessageToConversation(agentRequest, existing.get());
            return;
        }
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
        return this.conversationStore.get(sessionId).orElse(null);
    }

    @Override
    public List<Message> messages(Serializable sessionId) {
        return Collections.unmodifiableList(getConversationEntity(sessionId).messages());
    }




    @Override
    public TokenUsageEntity tokenUsage(Serializable sessionId) {
        return this.conversationStore.get(sessionId).orElseThrow().tokenUsageEntity();
    }

    @Override
    public void appendUserMessage(Serializable sessionId, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        ConversationEntity conversation = getConversationEntity(sessionId);
        conversation.messages().add(UserMessageEntity.from(text));
        this.conversationStore.save(sessionId, conversation);
    }

    @Override
    public void appendInternalUserMessage(Serializable sessionId, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        ConversationEntity conversation = getConversationEntity(sessionId);
        conversation.messages().add(UserMessageEntity.internal(text));
        this.conversationStore.save(sessionId, conversation);
    }

    @Override
    public void refreshBoundary(Serializable sessionId, LoopBoundary boundary, String customSystemPrompt) {
        refreshBoundary(sessionId, boundary, customSystemPrompt, null, null);
    }

    @Override
    public void refreshBoundary(Serializable sessionId, LoopBoundary boundary, String customSystemPrompt,
                                String task, List<String> toolList) {
        refreshBoundary(sessionId, boundary, customSystemPrompt, task, toolList, null);
    }

    @Override
    public void refreshBoundary(Serializable sessionId, LoopBoundary boundary, String customSystemPrompt,
                                String task, List<String> toolList, Workspace workspace) {
        ConversationEntity conversation = getConversationEntity(sessionId);
        setLeadingSystemMessage(conversation,
                SystemMessageEntity.builder().text(
                        assembleSystemPrompt(workspace != null ? workspace : resolveWorkspace(conversation),
                                customSystemPrompt, task, toolList, boundary)
                ).build(),
                sessionId);
    }

    @Override
    public void rebuildContext(ContextSummary contextSummary, Serializable sessionId) {
        rebuildContext(contextSummary, sessionId, false);
    }

    @Override
    public void rebuildContext(ContextSummary contextSummary, Serializable sessionId, boolean answeredTrailingUserTurn) {
        if (contextSummary == null) return;
        ConversationEntity conversation = getConversationEntity(sessionId);
        String summary = contextSummary.getSummary();
        try {
            log.info("【context-rebuild】 rebuilding context with summary: {}", summary);
            SystemMessageEntity systemMessage = conversation.systemMessageEntity();
            List<Message> latestToolMessageAndAiMessage = retainableTail(conversation, answeredTrailingUserTurn);
            String protectedContext = protectedContextForRebuild(sessionId, conversation);

            List<Message> rebuilt = new ArrayList<>();
            rebuilt.add(systemMessage);
            // Protection: re-attach the rendered session plan (or, as a fallback, the first
            // pure-text AI message of the history) so the produced plan survives compaction.
            //
            // The plan is re-attached as a SYSTEM message on purpose. Emitting it as an AiMessage
            // fabricates an assistant turn the model never produced: it carries no thinking, so the
            // codec sends it without `reasoning_content`. Thinking-mode models (Qwen / DashScope)
            // then reject the whole request with 400 "The `reasoning_content` in the thinking mode
            // must be passed back to the API", which is exactly what happened right after every
            // model compaction. A system message is also immune to DefaultManualCompacter, which
            // only squeezes AiMessageEntity rounds, so the plan stays intact either way.
            if (protectedContext != null && !protectedContext.isBlank()) {
                rebuilt.add(SystemMessageEntity.builder().text(
                        String.format("""
                                        The session plan produced earlier is reproduced below. Keep following it:

                                        %s
                                        """,
                                protectedContext)
                ).build());
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
            if (latestToolMessageAndAiMessage.isEmpty()) {
                // Nothing of the last round could be kept: end the rebuilt context with a user
                // turn so the next request stays well-formed (a tools request made only of system
                // messages is rejected by every OpenAI-protocol provider).
                rebuilt.add(UserMessageEntity.from(CONTINUE_AFTER_COMPACTION_PROMPT));
            } else {
                rebuilt.addAll(latestToolMessageAndAiMessage);
            }

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
     * 1. application state supplied by {@link ContextAttachmentProvider}, when present;
     * 2. otherwise the first pure-text (tool-call-free) AI message in the history
     *    (the message that originally proposed the work).
     */
    private String protectedContextForRebuild(Serializable sessionId, ConversationEntity conversation) {
        String protectedContext = this.contextAttachmentProvider.attachment(sessionId).orElse(null);
        if (protectedContext != null && !protectedContext.isBlank()) {
            return protectedContext;
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
     * Find the latest interaction in the conversation: the last assistant turn together with the tool
     * results that follow it, or — when the history ends with the user's newest input (a compaction
     * triggered before the model answered it) — that user turn.
     *
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

            if (message instanceof UserMessageEntity) {
                // Keep the newest user turn: a compaction that runs before the model answered it
                // must not swallow the request that is currently being worked on.
                result.addFirst(message);
                break;
            }
        }

        return result;
    }

    /**
     * The part of the history that may survive a rebuild.
     *
     * <p>Thinking-mode APIs (DeepSeek V4 flash, Qwen, ...) reject a request that carries {@code tools}
     * but holds an assistant message without {@code reasoning_content}:
     * <i>"The reasoning_content in the thinking mode must be passed back to the API"</i>. Every
     * assistant message of the session has to carry the reasoning the model produced, no matter
     * whether that turn called a tool or not. A rebuilt context is exactly where such a message used
     * to appear — an assistant turn whose reasoning was never persisted was carried over while the
     * rest of the history was dropped, and the very next request failed with a 400.
     *
     * <p>The tail is therefore only kept when every assistant message it holds still carries its
     * reasoning (and tool results are only kept together with the assistant turn that requested
     * them). Otherwise the tail is dropped — the summary already covers it — and the caller appends
     * a short user turn instead so the next request stays well-formed.
     *
     * @param answeredTrailingUserTurn true when the round being rebuilt away was the model's answer
     *        to the trailing user turn (a model-initiated {@code compact_context} call). Such a turn
     *        is dropped even though it needs no reasoning: keeping it would present the model a
     *        request it has already served, and it would serve it again — with a second compaction.
     *        The rebuilt context then ends with the "continue after compaction" instruction instead.
     */
    private List<Message> retainableTail(ConversationEntity conversation, boolean answeredTrailingUserTurn) {
        List<Message> tail = findLatestInteraction(conversation);
        if (tail.isEmpty()) {
            return tail;
        }
        if (answeredTrailingUserTurn && tail.stream().anyMatch(UserMessageEntity.class::isInstance)) {
            log.info("【context-rebuild】dropping the user turn this compaction round answered: it is already served, "
                    + "keeping it would make the model compact again");
            return List.of();
        }
        boolean hasAssistant = tail.stream().anyMatch(AiMessageEntity.class::isInstance);
        if (!hasAssistant) {
            if (tail.stream().noneMatch(ToolMessageEntity.class::isInstance)) {
                return tail;    // a plain user turn needs no reasoning and is always valid
            }
            log.warn("【context-rebuild】dropping the tail of the rebuilt context: tool results without their assistant turn");
            return List.of();
        }
        if (tail.stream().allMatch(DefaultConversationManager::isThinkingSafe)) {
            return tail;
        }
        log.warn("【context-rebuild】dropping the latest round from the rebuilt context: its assistant message carries no "
                + "reasoning, a thinking-mode API rejects such a message (reasoning_content must be passed back)");
        return List.of();
    }

    /** True when the message is not an assistant turn, or when it still carries the reasoning the model produced. */
    private static boolean isThinkingSafe(Message message) {
        if (!(message instanceof AiMessageEntity aiMessage)) {
            return true;
        }
        String thinking = aiMessage.getThinking();
        return thinking != null && !thinking.isBlank();
    }



    /**
     * Assembles the three-part system prompt text for the given workspace / custom
     * prompt / loop boundary (only the default template is formatted).
     */
    private String assembleSystemPrompt(Workspace workspace, String customSystemPrompt, String task,
                                        List<String> toolList, LoopBoundary boundary) {
        return this.systemPromptAssembler.assemble(this.defaultSystemPrompt, workspace, customSystemPrompt,
                task, toolList, boundary);
    }

    private SystemMessageEntity buildSystemMessage(AgentRequest agentRequest,Workspace workspace) {
        return SystemMessageEntity.builder().text(
                assembleSystemPrompt(workspace, agentRequest.getSystemPrompt(), agentRequest.getTask(),
                        agentRequest.getToolList(), agentRequest.runtimeParametersOrDefault().getLoopBoundary())
        ).build();
    }

    /**
     * Refresh the leading system message of an existing conversation so the current
     * loop boundary / custom prompt of this request is visible to the model.
     *
     * <p>Only rebuilds when the assembled text actually changed. Legacy conversations
     * whose leading message is not a system message get the system message inserted
     * at index 0. The  field is kept in
     * sync so later context rebuilds reuse the same message.</p>
     */
    private void refreshSystemMessage(AgentRequest agentRequest, ConversationEntity conversation) {
        Workspace workspace = agentRequest.getWorkspace() != null
                ? agentRequest.getWorkspace()
                : resolveWorkspace(conversation);
        setLeadingSystemMessage(conversation, buildSystemMessage(agentRequest, workspace), agentRequest.sessionIdOrDefault());
    }

    /**
     * Puts the given system message at index 0 of the conversation message stream
     * (replacing an existing leading system message, or inserting one for legacy
     * conversations), keeps  in sync and
     * persists the change. No-op when the leading message already carries the same text.
     */
    private void setLeadingSystemMessage(ConversationEntity conversation, SystemMessageEntity systemMessage, Serializable sessionId) {
        List<Message> messages = conversation.messages();
        Message first = messages.isEmpty() ? null : messages.getFirst();
        if (first instanceof SystemMessageEntity existing && existing.text().equals(systemMessage.text())) {
            return;
        }
        if (first instanceof SystemMessageEntity) {
            messages.set(0, systemMessage);
        } else {
            messages.addFirst(systemMessage);
        }
        ConversationEntity refreshed = new ConversationEntity(conversation.sessionId(), conversation.sessionName(),
                messages, conversation.tokenUsageEntity(), systemMessage,
                conversation.workspaceSpec());
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

        Workspace liveWorkspace = agentRequest.getWorkspace();
        SystemMessageEntity systemMessage = buildSystemMessage(agentRequest,liveWorkspace);

        ConversationEntity conversation = ConversationEntity.empty(agentRequest.getSessionName(),
                agentRequest.getWorkspaceSpec(), systemMessage, sessionId, new LinkedList<>());

        conversation.messages().add(systemMessage);
        conversation.messages().add(UserMessageEntity.from(agentRequest.getInput()));
        this.conversationStore.save(sessionId, conversation);
    }

    private Workspace resolveWorkspace(ConversationEntity conversation) {
        WorkspaceSpec workspaceSpec = conversation.workspaceSpec();
        if (workspaceSpec == null || workspaceSpec.workspaceRef() == null || workspaceManager == null) {
            return null;
        }
        try {
            return workspaceManager.acquire(workspaceSpec.workspaceRef());
        } catch (RuntimeException e) {
            log.warn("Failed to resolve workspace {} for session {}: {}",
                    workspaceSpec.workspaceRef().id(), conversation.sessionId(), e.getMessage());
            return null;
        }
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
