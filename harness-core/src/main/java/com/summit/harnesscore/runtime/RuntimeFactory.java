package com.summit.harnesscore.runtime;

import com.summit.harnesscore.model.ModelInvoker;


public interface RuntimeFactory {
    ExecutionRuntime createChatModelRuntime(ModelInvoker chatModelInvoker, Workspace workspace);
    ExecutionRuntime createStreamingModelRuntime(ModelInvoker streamingModelInvoker, Workspace workspace);
}
