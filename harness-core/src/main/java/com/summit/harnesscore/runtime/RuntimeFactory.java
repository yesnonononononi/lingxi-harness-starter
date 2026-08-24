package com.summit.harnesscore.runtime;

import com.summit.harnesscore.model.ChatModelInvoker;
import com.summit.harnesscore.model.StreamingModelInvoker;

public interface RuntimeFactory {
    ExecutionRuntime createChatModelRuntime(ChatModelInvoker chatModelInvoker);
    ExecutionRuntime createStreamingModelRuntime(StreamingModelInvoker streamingModelInvoker);
}
