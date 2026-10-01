package com.summit.core.agent;


import com.summit.core.compact.ContextUsageMetric;
import com.summit.core.conf.McpConfig;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.TokenUsageEntity;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import lombok.ToString;
import lombok.extern.jackson.Jacksonized;

import java.time.Instant;
import java.util.*;


/** Represents an execution of a task by an agent. */
@Builder
@Jacksonized
@ToString
@Data
public class Execution {
    /** The unique identifier for the execution. */
    private String id;
    /** The unique identifier for the agent. */
    private String agentId;
    /** The current state of the execution. */
    private ExecutionState executionState;
    /** The timestamp when the execution was created. */
    private Instant createAt;
    /** The timestamp when the execution started. */
    private Instant startAt;
    /** The timestamp when the execution completed. */
    private Instant completedAt;
    /** The request for the execution. */
   @NonNull
   private AgentRequest agentRequest;
    /** The messages for the execution. */
    private List<Message> messages;
    /** The final assistant message produced by this execution. */
    private AiMessageEntity aiMessage;
    /** The token usage for the execution. */
    private TokenUsageEntity tokenUsage;

    private McpConfig mcpConfig;

    private String errorMessage;
    /** require thinking text or not */
    private boolean thinking;
    /** require streaming or not */
    private boolean streaming;

    private int maxSteps;
    /** Model attempts consumed across all resumes of this execution. */
    private int modelAttempts;

    /**
     *  having value when the end of execution
     */
    private ContextUsageMetric contextUsageMetric;

    public void cancel(){
        this.executionState = ExecutionState.CANCELLED;
        this.completedAt = Instant.now();
    }
    public static Execution create(AgentRequest request, String agentId) {
        Objects.requireNonNull(request, "agentRequest");
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            throw new IllegalArgumentException("AgentRequest.messages must contain the conversation context");
        }
        return Execution.builder()
                .id(request.getExecutionId() == null
                        || request.getExecutionId().isBlank()
                        ? UUID.randomUUID().toString() : request.getExecutionId())
                .mcpConfig(request.getMcpConfig())
                .agentId(agentId).agentRequest(request)
                .messages(new ArrayList<>(request.getMessages()))
                .executionState(ExecutionState.CREATED).createAt(Instant.now()).build();
    }

    public void fillContextUsage(ContextUsageMetric metric){
        if(metric == null)return;
        this.contextUsageMetric = metric;
    }
    public void start(){
        this.executionState = ExecutionState.RUNNING;
        this.startAt = Instant.now();
    }
    public void complete(){
        this.executionState = ExecutionState.COMPLETED;
        this.completedAt = Instant.now();
    }
    public void fail(String errorMessage){
        this.errorMessage = errorMessage;
        this.executionState = ExecutionState.FAILED;
        this.completedAt = Instant.now();
    }

    public void incrementModelAttempts() {
        this.modelAttempts++;
    }

    public void resume(){
        this.executionState = ExecutionState.RUNNING;
    }

    public void suspended(){
        this.executionState = ExecutionState.SUSPENDED;
    }


    public Map<String,Object> eventMetaData(){
        return agentRequest.runtimeParametersOrDefault().getEventMetaData();
    }

}
