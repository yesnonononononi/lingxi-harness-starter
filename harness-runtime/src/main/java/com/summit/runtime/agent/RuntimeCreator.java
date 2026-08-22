package com.summit.runtime.agent;

import com.summit.harnesscore.runtime.ExecutionRuntime;
import com.summit.harnesscore.runtime.Workspace;
import com.summit.harnesscore.tool.ToolRegistry;
import com.summit.runtime.ChatModelRuntimeProcessor;
import com.summit.runtime.StreamingChatModelProcessor;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;


public class RuntimeCreator {
    public static ExecutionRuntime create(ChatModel model, ToolRegistry toolRegistry, Workspace workspace){
        return new ChatModelRuntimeProcessor(model,toolRegistry,workspace);
    }
    public static ExecutionRuntime create(StreamingChatModel model, ToolRegistry toolRegistry, Workspace workspace){
        return new StreamingChatModelProcessor(model,toolRegistry,workspace);
    }
}
