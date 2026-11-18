package com.summit.core.conversation.event;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.Map;

/** Published when an agent-loop execution starts. */
@Getter
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ExecutionStartEvent extends AbstractEvent implements RuntimeEvent {

    public ExecutionStartEvent(String executionId, Map<String, Object> metaData) {
        super(executionId, metaData);
    }

    /** Compatibility constructor for callers without selected event metadata. */
    public ExecutionStartEvent(String executionId) {
        super(executionId);
    }

    @Override
    public String type() {
        return RuntimeEventType.EXECUTION_STARTED.type();
    }
}
