package com.summit.core.agent;


public interface Agent {
    String id();
    Execution execute(AgentRequest agentRequest);
}
