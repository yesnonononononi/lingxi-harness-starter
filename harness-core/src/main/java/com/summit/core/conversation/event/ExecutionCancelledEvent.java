package com.summit.core.conversation.event;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.Map;

/**
 * Published when an agent-loop execution is cancelled (externally stopped or interrupted) rather than
 * completing on its own.
 */
@Getter
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ExecutionCancelledEvent extends AbstractEvent implements RuntimeEvent {

    /** Usage accumulated before the cancellation; {@code null} = not collected. */
    private final TokenInfo tokenInfo;

    public ExecutionCancelledEvent(String executionId, TokenInfo tokenInfo, Map<String, Object> metaData) {
        super(executionId, metaData);
        this.tokenInfo = tokenInfo;
    }

    /** Compatibility constructor for callers without token usage. */
    public ExecutionCancelledEvent(String executionId, Map<String, Object> metaData) {
        this(executionId, null, metaData);
    }

    /** Compatibility constructor for callers without selected event metadata. */
    public ExecutionCancelledEvent(String executionId) {
        this(executionId, null, Map.of());
    }

    @Override
    public String type() {
        return RuntimeEventType.EXECUTION_CANCELLED.type();
    }
}
