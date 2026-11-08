package com.summit.core.audit;

import com.summit.core.agent.Execution;

@FunctionalInterface
public interface AgentAuditor <T>{
    T analysis(Execution execution);
}
