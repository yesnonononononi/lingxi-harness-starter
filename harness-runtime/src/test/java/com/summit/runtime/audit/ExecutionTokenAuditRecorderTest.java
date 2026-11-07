package com.summit.runtime.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.AgentRuntimeParameters;
import com.summit.core.agent.Execution;
import com.summit.core.agent.ExecutionState;
import com.summit.core.audit.AuditStatus;
import com.summit.core.audit.TokenAuditor;
import com.summit.core.conversation.event.AgentAuditFailedEvent;
import com.summit.core.conversation.event.AgentAuditRecordedEvent;
import com.summit.core.conversation.event.AgentTokenAuditFailedEvent;
import com.summit.core.conversation.event.AgentTokenAuditRecordedEvent;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.event.RuntimeEventType;
import com.summit.core.conversation.message.ToolMessageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.json.ExecutionJson;
import com.summit.core.runtime.RuntimeListener;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.conversation.DefaultTokenizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionTokenAuditRecorderTest {
    private final List<AgentAuditRecordedEvent> recorded = new ArrayList<>();
    private final List<AgentAuditFailedEvent> failed = new ArrayList<>();
    private final RuntimeListener listener = new RuntimeListener() {
        @Override
        public void onAuditRecorded(AgentAuditRecordedEvent event) {
            recorded.add(event);
        }

        @Override
        public void onAuditFailed(AgentAuditFailedEvent event) {
            failed.add(event);
        }
    };
    private final TokenAuditor failingAuditor = messages -> {
        throw new IllegalStateException("audit unavailable");
    };

    @ParameterizedTest
    @EnumSource(value = ExecutionState.class, names = {"COMPLETED", "FAILED", "CANCELLED"})
    void publishesFailureWithExecutionIdentityAndSerializableReason(ExecutionState state) {
        Execution execution = execution(state);
        ExecutionTokenAuditRecorder recorder = new ExecutionTokenAuditRecorder(
                failingAuditor, new RuntimeEventPublisher(List.of(listener)));

        assertDoesNotThrow(() -> recorder.record(execution));

        assertTrue(recorded.isEmpty());
        assertEquals(1, failed.size());
        AgentTokenAuditFailedEvent event = assertInstanceOf(AgentTokenAuditFailedEvent.class, failed.getFirst());
        assertEquals(execution.getId(), event.executionId());
        assertEquals(state, event.executionState());
        assertEquals(state, execution.getExecutionState());
        assertEquals(RuntimeEventType.AGENT_TOKEN_AUDIT_FAILED.type(), event.type());
        assertEquals(IllegalStateException.class.getName(), event.errorType());
        assertEquals("audit unavailable", event.errorMessage());
        assertNotNull(event.timestamp());
        assertEquals(execution.eventMetaData(), event.eventMetaData());
        assertThrows(UnsupportedOperationException.class, () -> event.eventMetaData().put("turnId", "other"));

        ObjectMapper mapper = ExecutionJson.newObjectMapper();
        JsonNode json = mapper.valueToTree(event);
        assertEquals("audit-run", json.path("executionId").asText());
        assertEquals("turn-1", json.path("metaData").path("turnId").asText());
        assertEquals(event.errorType(), json.path("errorType").asText());
        assertEquals(event.errorMessage(), json.path("errorMessage").asText());
    }

    @ParameterizedTest
    @EnumSource(value = ExecutionState.class, names = {"CREATED", "RUNNING", "SUSPENDED"})
    void skipsAuditingNonterminalExecutions(ExecutionState state) {
        AtomicInteger attempts = new AtomicInteger();
        TokenAuditor auditor = messages -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("must not audit");
        };
        ExecutionTokenAuditRecorder recorder = new ExecutionTokenAuditRecorder(
                auditor, new RuntimeEventPublisher(List.of(listener)));

        recorder.record(execution(state));

        assertEquals(0, attempts.get());
        assertTrue(recorded.isEmpty());
        assertTrue(failed.isEmpty());
    }

    @Test
    void keepsPartialAuditFailuresInTheRecordedResult() {
        Execution execution = execution(ExecutionState.COMPLETED);
        execution.getMessages().add(ToolMessageEntity.builder()
                .id("missing-call").name("read").toolOutput("result").build());
        DefaultTokenAuditor auditor = new DefaultTokenAuditor(
                new DefaultTokenizer(), AgentConfig.builder().maxTokens(1000).build());
        ExecutionTokenAuditRecorder recorder = new ExecutionTokenAuditRecorder(
                auditor, new RuntimeEventPublisher(List.of(listener)));

        recorder.record(execution);

        assertTrue(failed.isEmpty());
        assertEquals(1, recorded.size());
        AgentTokenAuditRecordedEvent event = assertInstanceOf(AgentTokenAuditRecordedEvent.class, recorded.getFirst());
        assertEquals(AuditStatus.FAILED, event.contextAudit().toolTokens().getStatus());
        assertEquals(AuditStatus.SUCCESS, event.contextAudit().userTokens().getStatus());
    }

    @Test
    void continuesDeliveringFailureWhenOneListenerThrows() {
        RuntimeListener throwingListener = new RuntimeListener() {
            @Override
            public void onAuditFailed(AgentAuditFailedEvent event) {
                throw new IllegalStateException("listener unavailable");
            }
        };
        ExecutionTokenAuditRecorder recorder = new ExecutionTokenAuditRecorder(
                failingAuditor, new RuntimeEventPublisher(List.of(throwingListener, listener)));

        assertDoesNotThrow(() -> recorder.record(execution(ExecutionState.COMPLETED)));

        assertEquals(1, failed.size());
        assertTrue(recorded.isEmpty());
    }

    @Test
    void doesNotPropagateOrRetryFailurePublicationErrors() {
        AtomicInteger publications = new AtomicInteger();
        RuntimeEventPublisher publisher = new RuntimeEventPublisher(List.of()) {
            @Override
            public void onAuditFailed(AgentAuditFailedEvent event) {
                publications.incrementAndGet();
                throw new IllegalStateException("publisher unavailable");
            }
        };
        Execution execution = execution(ExecutionState.COMPLETED);
        ExecutionTokenAuditRecorder recorder = new ExecutionTokenAuditRecorder(failingAuditor, publisher);

        assertDoesNotThrow(() -> recorder.record(execution));

        assertEquals(1, publications.get());
        assertEquals(ExecutionState.COMPLETED, execution.getExecutionState());
    }

    private Execution execution(ExecutionState state) {
        AgentRequest request = AgentRequest.builder().executionId("audit-run")
                .messages(List.of(UserMessageEntity.from("task")))
                .runtimeParameters(AgentRuntimeParameters.builder()
                        .eventMetaData(Map.of("turnId", "turn-1")).build())
                .build();
        Execution execution = Execution.create(request, "agent-1");
        execution.setExecutionState(state);
        return execution;
    }
}
