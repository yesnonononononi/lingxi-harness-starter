package com.summit.runtime;

import com.summit.harnesscore.agent.Execution;
import com.summit.harnesscore.conversation.context.RuntimeContext;
import com.summit.harnesscore.runtime.ExecutionRuntime;

import dev.langchain4j.model.chat.StreamingChatModel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public class StreamingChatModelProcessor implements ExecutionRuntime {
    private final RuntimeContext<StreamingChatModel> runtimeContext;


    @Override
    public Execution execute(Execution execution) {
        return null;
    }
}
