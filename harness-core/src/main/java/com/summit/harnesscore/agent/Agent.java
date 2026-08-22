package com.summit.harnesscore.agent;


public interface Agent {
    String id();
    Execution execute(AgentRequest agentRequest);
}
