package com.summit.runtime;

import com.summit.harnesscore.agent.Execution;
import com.summit.harnesscore.runtime.ExecutionRuntime;
import com.summit.harnesscore.runtime.Workspace;
import com.summit.harnesscore.tool.ToolRegistry;
import dev.langchain4j.model.chat.StreamingChatModel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public class StreamingChatModelProcessor implements ExecutionRuntime {
    private final StreamingChatModel model;
    private final ToolRegistry toolRegistry;
    private final Workspace workspace;


    @Override
    public Execution execute(Execution execution) {
        return null;
    }
}
