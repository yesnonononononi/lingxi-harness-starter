package com.summit.harnesscore.agent;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.output.TokenUsage;
import lombok.Builder;
import lombok.Data;
import lombok.ToString;

import java.time.Instant;
import java.util.List;


/**
 * Represents an execution of a task by an agent.
 */
@Builder
@ToString
@Data
public class Execution {
    /**
     * The unique identifier for the execution.
     */
    private String id;
    /**
     * The unique identifier for the agent.
     */
    private String agentId;
    /**
     * The current state of the execution.
     */
    private ExecutionState executionState;
    /**
     * The timestamp when the execution was created.
     */
    private Instant createAt;
    /**
     * The timestamp when the execution started.
     */
    private Instant startAt;
    /**
     * The timestamp when the execution completed.
     */
    private Instant completedAt;
    /**
     * The request for the execution.
     */
    private AgentRequest agentRequest;
    /**
     * The messages for the execution.
     */
    private List<ChatMessage> messages;
    /**
     * The token usage for the execution.
     */
    private TokenUsage tokenUsage;

    private String errorMessage;
    /**
     * require thinking text or not
     */
    private boolean thinking;
    /**
     * require streaming or not
     */
    private boolean streaming;


    public void cancel(){
        this.executionState = ExecutionState.CANCELLED;
        this.completedAt = Instant.now();
    }
    public void create(){
        this.executionState = ExecutionState.CREATED;
        this.createAt = Instant.now();
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


    public void addMessage(ChatMessage message) {
        this.messages.add(message);
    }
}
