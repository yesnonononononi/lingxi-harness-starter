package com.summit.core.runtime.loop;

import com.summit.core.agent.Execution;
import com.summit.core.conversation.ConversationManager;
import com.summit.core.conversation.api.ChatResponseEntity;


import com.summit.core.conversation.api.ConversationTranscriptSink;
import com.summit.core.tool.ToolExecuteResult;
import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * The class exists in {@link LoopContext}.
 * Do not trust the {@code toolExecutionResults} or {@code response} because their identities are determined after {@link ConversationManager} processes them, compare with {@link ConversationTranscriptSink} unless you want to save them in advance
 */
@Builder
@Data
public class LoopMessages {
    Execution execution;
    List<ToolExecuteResult> toolExecuteResults;
    ChatResponseEntity response;

    @Builder.Default
    private int version = 0;

    public void toolResults(List<ToolExecuteResult> toolExecuteResults){
        this.toolExecuteResults = toolExecuteResults;
        this.version++;
    }

    public void response(ChatResponseEntity response){
        this.response = response;
        this.version++;
    }

    public void clear(){
        this.toolExecuteResults = null;
        this.response = null;
        this.version = 0;
    }

}
