package com.summit.core.conversation.event;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.Map;

/**
 * Published when an agent-loop execution is suspended cooperatively instead of running to a terminal
 * state.
 *
 * <p>Suspension is not terminal: the execution keeps its committed round and its request-scoped
 * resources until a later resume or cancel. The counterpart {@link ExecutionResumedEvent} is
 * published when that resume happens.</p>
 */
@Getter
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ExecutionSuspendedEvent extends AbstractEvent implements RuntimeEvent {

    public ExecutionSuspendedEvent(String executionId, Map<String, Object> metaData) {
        super(executionId, metaData);
    }

    /** Compatibility constructor for callers without selected event metadata. */
    public ExecutionSuspendedEvent(String executionId) {
        super(executionId);
    }

    @Override
    public String type() {
        return RuntimeEventType.EXECUTION_SUSPENDED.type();
    }
}
