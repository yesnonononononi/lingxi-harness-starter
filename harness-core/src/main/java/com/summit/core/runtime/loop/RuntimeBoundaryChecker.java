package com.summit.core.runtime.loop;

import com.summit.core.agent.Execution;

/**
 * Decision point of the agent loop: every implementation answers whether the loop keeps going
 * (iteration budget, token budget, and other runtime limits).
 */
public interface RuntimeBoundaryChecker {
    /** Checks the next model attempt after all before-model callbacks have appended their input. */
    CheckPointResult before(Execution execution);

    /** Checks an appended ordinary tool round before its checkpoint is saved. */
    CheckPointResult after(Execution execution);
}
