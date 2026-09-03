package com.summit.core.runtime;

import com.summit.core.agent.Execution;

public interface CheckPointer {
    boolean beforeCheckpoint(Execution execution);
    boolean afterCheckpoint(Execution execution);
}
