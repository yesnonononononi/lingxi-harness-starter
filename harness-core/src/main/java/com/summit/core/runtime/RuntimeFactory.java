package com.summit.core.runtime;

import com.summit.core.model.ModelInvoker;
import com.summit.core.runtime.workspace.Workspace;

public interface RuntimeFactory {

    ExecutionRuntime createChatModelRuntime(String executionId, ModelInvoker chatModelInvoker, Workspace workspace);

    ExecutionRuntime createStreamingModelRuntime(String executionId, ModelInvoker streamingModelInvoker, Workspace workspace);
}
