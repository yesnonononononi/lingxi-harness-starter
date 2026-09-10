package com.summit.core.runtime;

import com.summit.core.agent.Execution;

/**
 * Decision point of the agent loop: every implementation answers whether the loop keeps going
 * (iteration budget, token budget, /pause, /stop, ...).
 */
public interface CheckPointer {
    boolean beforeCheckpoint(Execution execution);
    boolean afterCheckpoint(Execution execution);
}
