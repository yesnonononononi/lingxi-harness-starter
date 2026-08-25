package com.summit.runtime.conversation;

import com.summit.harnesscore.agent.AgentRequest;
import com.summit.harnesscore.compact.ContextCompacter;
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


@Getter
@Slf4j
public class DefaultConversationManager implements ConversationManager {
    private final Workspace workspace;
    private final List<ChatMessage> messages;
    private TokenUsage tokenUsage;
    private SystemMessage originalSystemMessage = null;
    private final RuntimeEventPublisher runtimeEventPublisher;
    private final ContextCompacter contextCompacter;

    public DefaultConversationManager(Workspace workspace, RuntimeEventPublisher runtimeEventPublisher, ContextCompacter contextCompacter) {
        this.workspace = workspace;
        this.messages = new LinkedList<>();
        this.tokenUsage = new TokenUsage(0, 0, 0);
        this.runtimeEventPublisher = runtimeEventPublisher;
        this.contextCompacter = contextCompacter;
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
       this.contextCompacter.compact(expectedTokens,attemptNum,messages);
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
        }
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

                        tool usage rules (STRICT - choose the right tool before acting):
                         1. READ a file        -> use read_file, support startLine/endLine for partial reads.
                                                 NEVER use terminal commands (cat/type/Get-Content/more/tail) to read files.
                         2. CREATE/MODIFY a file -> use edit_file (INSERT_BEFORE/INSERT_AFTER/REPLACE/DELETE).
                                                 NEVER use terminal commands (Set-Content/Add-Content/echo/redirect >/sed) to modify files.
                         3. Web / external info -> use web_search. NEVER use terminal for network lookups.
                         4. execute_command is ONLY for real commands: build, run, install, git, start/stop services,
                                                 directory/file management (ls/cd/mkdir/cp/mv/rm). Its output is truncated
                                                 to a few thousand chars, so it is NOT suitable for reading or writing file content.
                         5. Always prefer the dedicated tool matching the operation: it saves tokens and avoids truncation.

                        context management
                         - call compact_context tool when existing conversation history exceeds 85 percent of the maximum token limit
                         - prioritize the use of tools corresponding to the functions to save token consumption
                        """,
                this.workspace.runTimeEnvironment().osType(),
                this.workspace.runTimeEnvironment().workDir()
        );
    }

}
