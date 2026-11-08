package com.summit.runtime.audit;

import com.summit.core.agent.Execution;
import com.summit.core.audit.TokenAuditor;
import com.summit.core.conversation.event.AgentTokenAuditFailedEvent;
import com.summit.core.conversation.event.AgentTokenAuditRecordedEvent;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.event.TokenInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;

/** Records and publishes the audit of a terminal execution. */
@Slf4j
@RequiredArgsConstructor
public class ExecutionTokenAuditRecorder {
    private final TokenAuditor tokenAuditor;
    private final RuntimeEventPublisher eventPublisher;

    public void record(Execution execution) {
        if (execution.getExecutionState() == null || !execution.getExecutionState().isTerminal()) {
            return;
        }
        try {
            var audit = tokenAuditor.analysis(execution);
            eventPublisher.onAuditRecorded(new AgentTokenAuditRecordedEvent(
                    execution.getId(), execution.getExecutionState(), TokenInfo.from(execution.getTokenUsage()),
                    audit, Instant.now(), execution.eventMetaData()));
        } catch (RuntimeException e) {
            log.warn("Execution token audit failed: executionId={}", execution.getId(), e);
            try {
                eventPublisher.onAuditFailed(new AgentTokenAuditFailedEvent(
                        execution.getId(), execution.getExecutionState(), e.getClass().getName(), e.getMessage(),
                        Instant.now(), execution.eventMetaData()));
            } catch (RuntimeException publishFailure) {
                log.warn("Failed to publish token audit failure: executionId={}", execution.getId(), publishFailure);
            }
        }
    }
}
