package com.summit.core.conversation.event;

import com.summit.core.agent.ExecutionState;
import com.summit.core.audit.token.TokenAuditDocument;

import java.time.Instant;
import java.util.Map;

/**
 * Token audit for one terminal execution. {@code tokenInfo} is accumulated usage for this
 * execution. {@code contextAudit} estimates the final message snapshot, including any retained
 * history, so its counts describe context composition rather than an additive session delta.
 */
public record AgentTokenAuditRecordedEvent(String executionId, ExecutionState executionState,
                                           TokenInfo tokenInfo, TokenAuditDocument contextAudit,
                                           Instant timestamp, Map<String, Object> eventMetaData)
        implements AgentAuditRecordedEvent {

    public AgentTokenAuditRecordedEvent {
        eventMetaData = eventMetaData == null ? Map.of() : Map.copyOf(eventMetaData);
    }

    @Override
    public String type() {
        return RuntimeEventType.AGENT_TOKEN_AUDIT_RECORDED.type();
    }
}
