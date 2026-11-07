package com.summit.core.conversation.event;

import com.summit.core.agent.ExecutionState;

import java.time.Instant;
import java.util.Map;

/** A terminal execution whose token audit could not be recorded. */
public record AgentTokenAuditFailedEvent(String executionId, ExecutionState executionState,
                                        String errorType, String errorMessage,
                                        Instant timestamp, Map<String, Object> eventMetaData)
        implements AgentAuditFailedEvent {

    public AgentTokenAuditFailedEvent {
        eventMetaData = eventMetaData == null ? Map.of() : Map.copyOf(eventMetaData);
    }

    @Override
    public String type() {
        return RuntimeEventType.AGENT_TOKEN_AUDIT_FAILED.type();
    }
}
