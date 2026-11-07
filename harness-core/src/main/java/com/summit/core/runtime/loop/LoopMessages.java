package com.summit.core.runtime.loop;

import com.summit.core.agent.Execution;
import com.summit.core.conversation.ConversationManager;
import com.summit.core.conversation.api.ChatResponseEntity;


import com.summit.core.conversation.api.ConversationTranscriptSink;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.ToolMessageEntity;
import com.summit.core.mcp.McpToolExecutor;
import com.summit.core.skill.SkillToolExecutor;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecutor;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;

/**
 * The class exists in {@link LoopContext}.
 * Do not trust the {@code toolExecutionResults} or {@code response} because their identities are determined after {@link ConversationManager} processes them, compare with {@link ConversationTranscriptSink} unless you want to save them in advance
 */
@Builder
@Data
public class LoopMessages {
   private Execution execution;
   private List<ToolExecuteResult> toolExecuteResults;
   private List<ToolMessageEntity> convertedToolMessages;
   private ChatResponseEntity response;

    @Builder.Default
    private int version = 0;

    public void toolResults(List<ToolExecuteResult> toolExecuteResults){
        this.toolExecuteResults = toolExecuteResults;
        this.convertedToolMessages = null;
        convert();  // flush cache
        this.version++;
    }

    public void response(ChatResponseEntity response){
        this.response = response;
        this.version++;
    }

    public void clear(){
        this.toolExecuteResults = null;
        this.response = null;
        this.convertedToolMessages = null;
        this.version = 0;
    }

    public List<Message> export(){
        if(response == null) return List.of();
        List<Message> toolMessageEntities = new LinkedList<>();
        toolMessageEntities.add(response.getAiMessageEntity());
        toolMessageEntities.addAll(this.convert());
        return toolMessageEntities;
    }


    public List<ToolMessageEntity> convert(){
        if(this.toolExecuteResults == null) return this.convertedToolMessages != null ? this.convertedToolMessages : List.of();



        var toolMessages = new LinkedList<ToolMessageEntity>();
        for (ToolExecuteResult result : this.getToolExecuteResults()) {
            ToolDefinition<?> definition = result.getToolSpecification();
            ToolExecutor executor = definition == null ? null : definition.executor();

            toolMessages.add(ToolMessageEntity.builder()
                    .id(result.getId())
                    .name(definition == null ? "unknown tool" : definition.name())
                    .toolOutput(result.getToolOutput())
                    .isError(!result.isSuccess())
                    .isMcpTool(executor instanceof McpToolExecutor)
                    .isSkillTool(executor instanceof SkillToolExecutor)
                    // The tool's own exit status travels with its message so a pruned skeleton can
                    // still tell a failed command from a successful one.
                    .exitCode(result.getExitCode())
                    .build());
        }

        this.convertedToolMessages = toolMessages;
        return toolMessages;
    }

}
