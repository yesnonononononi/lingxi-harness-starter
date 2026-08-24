package com.summit.runtime.conversation;

import com.summit.harnesscore.agent.AgentRequest;
import com.summit.harnesscore.compact.ContextSummary;
import com.summit.harnesscore.conversation.ConversationManager;
import com.summit.harnesscore.conversation.event.RuntimeEventPublisher;
import com.summit.harnesscore.runtime.Workspace;
import com.summit.harnesscore.tool.ToolExecuteResult;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.response.ChatResponse;

import dev.langchain4j.model.output.TokenUsage;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Stream;

@Getter
@Slf4j
public class DefaultConversationManager implements ConversationManager {
    private final Workspace workspace;
    private final List<ChatMessage> messages;
    private TokenUsage tokenUsage;
    private final Queue<ToolExecutionResultMessage> priorityQueue;
    private SystemMessage originalSystemMessage = null;
    private static final int DEFAULT_ATTEMPT_NUM = 3;
    private final List<Supplier<Integer>> squeezeStrategies;
    private final RuntimeEventPublisher runtimeEventPublisher;

    public DefaultConversationManager(Workspace workspace, RuntimeEventPublisher runtimeEventPublisher) {
        this.workspace = workspace;
        this.messages = new LinkedList<>();
        this.priorityQueue = new PriorityQueue<>(
                Comparator.comparingInt(tm -> ((ToolExecutionResultMessage) tm).text().length()).reversed()
        );

        this.tokenUsage = new TokenUsage(0, 0, 0);
        this.squeezeStrategies = new LinkedList<>(Arrays.asList(this::squeezeByLength, this::squeezeByAge));
        this.runtimeEventPublisher = runtimeEventPublisher;
    }

    @Override
    public void startConversation(AgentRequest agentRequest) {
        UserMessage userMessage = UserMessage.from(agentRequest.getInput());
        SystemMessage systemMessage = SystemMessage.from(getSystemMessage());
        this.originalSystemMessage = systemMessage;
        this.messages.addAll(List.of(systemMessage, userMessage));
    }

    @Override
    public void addMessage(ChatResponse chatResponse, @Nullable List<ToolExecuteResult> toolExecutionResultMessage) {
        AiMessage aiMessage = chatResponse.aiMessage();
        this.tokenUsage = this.tokenUsage.add(chatResponse.tokenUsage());

        this.messages.add(aiMessage);
        addToolMessages(toolExecutionResultMessage);
    }

    @Override
    public void endConversation() {

    }

    @Override
    public List<ChatMessage> messages() {
        return this.messages;
    }

    @Override
    public TokenUsage tokenUsage() {
        return this.tokenUsage;
    }

    @Override
    public void squeezeContext(Integer expectedTokens, Integer attemptNum) {

        int maxAttempts = Objects.requireNonNullElse(
                attemptNum,
                DEFAULT_ATTEMPT_NUM
        );

        int delta = 0;

        for (int attempt = 0;
             attempt < maxAttempts;
             attempt++) {

            int currentTokens =
                    this.tokenUsage.totalTokenCount() - delta;

            if (currentTokens <= expectedTokens) {
                break;
            }

            int curStep = attempt % this.squeezeStrategies.size();

            delta += this.squeezeStrategies
                    .get(curStep)
                    .get();
        }
    }

    @Override
    public void rebuildContext(ContextSummary contextSummary) {
        if (contextSummary == null) return;
        String summary = contextSummary.getSummary();
        try {
            log.info("【context-rebuild】 rebuilding context with summary: {}", summary);
            SystemMessage systemMessage = findOriginalSystemMessage();
            List<ChatMessage> latestToolMessageAndAiMessage = findLatestInteraction();
            this.messages.clear();
            this.messages.addAll(List.of(systemMessage, SystemMessage.from(
                    String.format("""
                                    The context_compact tool has been executed successfully, and the conversation history has been compressed into the following summary:
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
            this.messages.addAll(latestToolMessageAndAiMessage);
            log.info("【context-rebuild】successfully rebuild context with summary: {}", summary);
        } catch (Exception e) {
            log.error("【context-rebuild】 failed to rebuild context with summary: {}", summary, e);
        }
    }

    private Integer squeezeByLength() {
        ChatMessage chatMessage = this.priorityQueue.poll();
        if ((chatMessage instanceof ToolExecutionResultMessage toolMsg)) {
            String truncatedText = truncateText(toolMsg.text());
            if (truncatedText.equals(toolMsg.text())) {
                return 0;
            }
            ToolExecutionResultMessage newMsg = ToolExecutionResultMessage.toolExecutionResultMessage(toolMsg.id(), toolMsg.toolName(), truncatedText);
            if (Collections.replaceAll(this.messages, chatMessage, newMsg)) {
                log.info("Squeezed message: {} oldMessage:{}", newMsg, chatMessage);
                return estimateToken(toolMsg.text()) - estimateToken(truncatedText);
            }
            log.info("Failed to squeeze message: {} oldMessage:{}", newMsg, chatMessage);
        }
        return 0;
    }


    private Integer squeezeByAge() {
        ListIterator<ChatMessage> iterator = this.messages.listIterator();
        while (iterator.hasNext()) {
            if (iterator.next() instanceof ToolExecutionResultMessage toolMsg) {
                String truncateText = truncateText(toolMsg.text());

                if (truncateText.equals(toolMsg.text())) {
                    return 0;
                }

                ToolExecutionResultMessage newMsg = ToolExecutionResultMessage.toolExecutionResultMessage(toolMsg.id(), toolMsg.toolName(), truncateText);
                iterator.set(newMsg);

                this.priorityQueue.remove(toolMsg);

                return estimateToken(toolMsg.text()) - estimateToken(truncateText);
            }
        }
        return 0;
    }

    private String truncateText(String text) {
        if (text == null || text.isEmpty()) return "";
        return text.substring(0, (int) (text.length() * 0.2)) + "..." + text.substring(text.length() - (int) (text.length() * 0.2));
    }

    private void addToolMessages(List<ToolExecuteResult> results) {
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

            this.messages.add(message);
            this.priorityQueue.add(message);
        }
    }


    private Integer estimateToken(String text) {
        return (int) Math.ceil((double) text.length() / 4);
    }


    private List<ChatMessage> findLatestInteraction() {

        List<ChatMessage> result = new ArrayList<>();

        for (int i = messages.size() - 1; i >= 0; i--) {

            ChatMessage message = messages.get(i);

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


    private SystemMessage findOriginalSystemMessage() {
        if (this.originalSystemMessage != null) return this.originalSystemMessage;
        return (SystemMessage) this.messages.stream().filter(msg -> msg instanceof SystemMessage).findFirst().orElseThrow();
    }

    private String getSystemMessage() {
        return String.format("""
                        current
                         operation system : %s
                         workdir: %s
                        notice
                          you should attempt call compact_context tool if existing conversation history exceeds 85 percent of the maximum token limit
                        """,
                this.workspace.runTimeEnvironment().osType(),
                this.workspace.runTimeEnvironment().workDir()
        );
    }

}
