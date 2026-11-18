package com.summit.core.conversation.event;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.Map;

/** Published when a suspended execution is resumed and starts a new turn. */
@Getter
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ExecutionResumedEvent extends AbstractEvent implements RuntimeEvent {

    public ExecutionResumedEvent(String executionId, Map<String, Object> metaData) {
        super(executionId, metaData);
    }

    /** Compatibility constructor for callers without selected event metadata. */
    public ExecutionResumedEvent(String executionId) {
        super(executionId);
    }

    @Override
    public String type() {
        return RuntimeEventType.EXECUTION_RESUME.type();
    }
}
