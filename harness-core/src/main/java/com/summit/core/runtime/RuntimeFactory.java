package com.summit.core.runtime;

import com.summit.core.model.ModelInvoker;
import com.summit.core.runtime.workspace.Workspace;

import java.io.Serializable;


public interface RuntimeFactory {

    ExecutionRuntime createChatModelRuntime(Serializable sessionId, ModelInvoker chatModelInvoker, Workspace workspace);

    ExecutionRuntime createStreamingModelRuntime(Serializable sessionId, ModelInvoker streamingModelInvoker, Workspace workspace);
}
