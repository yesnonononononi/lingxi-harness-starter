package com.summit.core.conversation.event;

import com.summit.core.plan.Plan;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.io.Serializable;
import java.time.Instant;

/**
 * Published whenever a plan is created or mutated by the plan kernel tools, so the front-end can
 * (re)render the plan card in real time.
 *
 * <p>The event carries the whole {@link Plan} snapshot rather than a diff: plans are tiny, and a
 * full snapshot keeps the front-end stateless — every event simply replaces the rendered plan.</p>
 */
@AllArgsConstructor
@Getter
public class PlanUpdateEvent implements AgentEvent {

    /** The plan is rendered as a card waiting for the human decision (approve / revise / reject). */
    public static final String STATE_WAITING_APPROVAL = "WAITING_APPROVAL";
    /** The user approved the plan and the agent started implementing it. */
    public static final String STATE_APPROVED = "APPROVED";
    /** The user asked for a revised plan (back to planning). */
    public static final String STATE_REVISED = "REVISED";
    /** The user rejected the plan; it will never be implemented. */
    public static final String STATE_REJECTED = "REJECTED";
    /** The plan was implemented and is closed. */
    public static final String STATE_DONE = "DONE";
    /** The plan changed while being implemented (task progress). */
    public static final String STATE_UPDATED = "UPDATED";

    private final Serializable sessionId;
    private final String executionId;
    private final Plan plan;
    /** Card state, one of the {@code STATE_*} constants above. */
    private final String state;

    @Override
    public String executionId() {
        return executionId;
    }

    @Override
    public Instant timestamp() {
        return Instant.now();
    }
}
