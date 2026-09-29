package com.summit.core.runtime.loop;

import com.summit.core.agent.Execution;

/**
 * Decision point of the agent loop: every implementation answers whether the loop keeps going
 * (iteration budget, token budget, and other runtime limits).
 */
public interface RuntimeBoundaryChecker {
    CheckPointResult before(Execution execution);
    CheckPointResult after(Execution execution);
}
