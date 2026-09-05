package com.summit.core.conversation.event;

import com.summit.core.plan.PlanDecision;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.io.Serializable;
import java.time.Instant;

/**
 * Published whenever a PLANNING execution produces a structured plan, so the
 * front-end can render the plan decision (title + steps) in real time.
 */
@AllArgsConstructor
@Getter
public class PlanDecisionEvent implements AgentEvent {
    private final Serializable sessionId;
    private final String executionId;
    private final PlanDecision planDecision;

    @Override
    public String executionId() {
        return executionId;
    }

    @Override
    public Instant timestamp() {
        return Instant.now();
    }
}
