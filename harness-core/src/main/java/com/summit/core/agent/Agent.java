package com.summit.core.agent;


import com.summit.core.runtime.loop.ActiveExecutionRegistry;

public interface Agent<T extends AgentRequest> {
    String id();
    Execution execute(T agentRequest);
    Execution execute(Execution execution);

    /**
     * The result is returned after the first execution has been saved the {@link ActiveExecutionRegistry}
     * @param agentRequest The request for the execution
     * @return The raw execution
     */
    Execution createExecution(T agentRequest);
}
