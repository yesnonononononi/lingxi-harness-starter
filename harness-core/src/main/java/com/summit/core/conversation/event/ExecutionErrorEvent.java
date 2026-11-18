package com.summit.core.conversation.event;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.Map;

/** Published when an agent-loop execution fails. */
@Getter
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ExecutionErrorEvent extends AbstractEvent implements RuntimeEvent {

    private final String errMsg;
    private final String extraDes;
    /** Usage accumulated before the failure; {@code null} = not collected. */
    private final TokenInfo tokenInfo;

    public ExecutionErrorEvent(String errMsg, String extraDes, String executionId, TokenInfo tokenInfo,
                               Map<String, Object> metaData) {
        super(executionId, metaData);
        this.errMsg = errMsg;
        this.extraDes = extraDes;
        this.tokenInfo = tokenInfo;
    }

    /** Compatibility constructor for callers without token usage. */
    public ExecutionErrorEvent(String errMsg, String extraDes, String executionId, Map<String, Object> metaData) {
        this(errMsg, extraDes, executionId, null, metaData);
    }

    /** Compatibility constructor for callers without selected event metadata. */
    public ExecutionErrorEvent(String errMsg, String extraDes, String executionId) {
        this(errMsg, extraDes, executionId, null, Map.of());
    }

    @Override
    public String type() {
        return RuntimeEventType.EXECUTION_FAILED.type();
    }
}
