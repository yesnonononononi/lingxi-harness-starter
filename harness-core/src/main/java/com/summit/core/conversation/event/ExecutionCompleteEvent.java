package com.summit.core.conversation.event;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.Map;

/** Published when an agent-loop execution finishes normally, carrying its accumulated usage. */
@Getter
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ExecutionCompleteEvent extends AbstractEvent implements RuntimeEvent {

    private final TokenInfo tokenInfo;

    public ExecutionCompleteEvent(String executionId, TokenInfo tokenInfo, Map<String, Object> metaData) {
        super(executionId, metaData);
        this.tokenInfo = tokenInfo;
    }

    /** Compatibility constructor for callers without selected event metadata. */
    public ExecutionCompleteEvent(String executionId, TokenInfo tokenInfo) {
        this(executionId, tokenInfo, Map.of());
    }

    @Override
    public String type() {
        return RuntimeEventType.EXECUTION_COMPLETED.type();
    }
}
