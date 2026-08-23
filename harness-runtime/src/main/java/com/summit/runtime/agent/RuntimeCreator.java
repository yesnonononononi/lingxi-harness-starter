package com.summit.runtime.agent;

import com.summit.harnesscore.conversation.context.RuntimeContext;
import com.summit.harnesscore.runtime.ExecutionRuntime;
import com.summit.harnesscore.runtime.Workspace;
import com.summit.harnesscore.tool.ToolRegistry;
import com.summit.runtime.ChatModelRuntimeProcessor;
import com.summit.runtime.StreamingChatModelProcessor;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.output.TokenUsage;

import java.util.LinkedList;


public class RuntimeCreator {
    public static ExecutionRuntime create(ChatModel model, ToolRegistry toolRegistry, Workspace workspace){
        return new ChatModelRuntimeProcessor(buildRuntimeContext(model, toolRegistry, workspace));
    }
    public static ExecutionRuntime create(StreamingChatModel model, ToolRegistry toolRegistry, Workspace workspace){
        return new StreamingChatModelProcessor(buildRuntimeContext(model,toolRegistry,workspace));
    }

    private static <T>RuntimeContext<T> buildRuntimeContext(T model, ToolRegistry toolRegistry, Workspace workspace){
        return RuntimeContext.<T>builder()
                .model(model)
                .toolRegistry(toolRegistry)
                .workspace(workspace)
                .tokenUsage(new TokenUsage(0,0,0))
                .messages(new LinkedList<>())
                .build();
    }
}
