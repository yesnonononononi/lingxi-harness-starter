package com.summit.harnesscore.runtime;

import com.summit.harnesscore.model.ModelInvoker;

import java.io.Serializable;


public interface RuntimeFactory {

    ExecutionRuntime createChatModelRuntime(Serializable sessionId, ModelInvoker chatModelInvoker, Workspace workspace);

    ExecutionRuntime createStreamingModelRuntime(Serializable sessionId, ModelInvoker streamingModelInvoker, Workspace workspace);
}
