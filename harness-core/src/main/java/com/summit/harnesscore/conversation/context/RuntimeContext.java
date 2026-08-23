package com.summit.harnesscore.conversation.context;

import com.summit.harnesscore.runtime.Workspace;
import com.summit.harnesscore.tool.ToolRegistry;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.output.TokenUsage;
import lombok.Builder;

import java.util.LinkedList;
import java.util.List;

@Builder
public record RuntimeContext<T>(T model, ToolRegistry toolRegistry, Workspace workspace, List<ChatMessage> messages,
                                TokenUsage tokenUsage) {


    public RuntimeContext<Void> empty(){
        return RuntimeContext.<Void>builder()
                .model(null)
                .toolRegistry(new ToolRegistry())
                .workspace(null)
                .messages(new LinkedList<>())
                .tokenUsage(new TokenUsage())
                .build();
    }


}
