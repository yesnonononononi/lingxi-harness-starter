package com.summit.core.agent;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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


/**
 * Represents an execution of a task by an agent.
 *
 * <p>Lifecycle changes go through the checked methods {@link #startChecked()},
 * {@link #resumeChecked()}, {@link #suspendChecked()}, {@link #completeChecked()},
 * {@link #failChecked(String)} and {@link #cancelChecked()}, each of which verifies the current
 * state before mutating. These are the only entry points for changing state during a run.</p>
 *
 * <p>The plain {@code start} / {@code complete} / {@code fail} / ... methods remain
 * <b>unvalidated</b> for two reasons: {@link #restoreTerminalState} needs to set an outcome without
 * a legal transition to make, and snapshots persisted while an execution was already terminal must
 * still decode. Ordinary runtime code should use the checked variants.</p>
 *
 * <p>{@code writeToolExecuted} is retired: snapshots written before its removal still carry the
 * key, so it is ignored by name instead of failing snapshot restore.</p>
 */
@Builder
@Jacksonized
@ToString
@Data
@JsonIgnoreProperties("writeToolExecuted")
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

    /**
     * Retired copy of {@code agentRequest.mcpConfig}.
     *
     * <p>Kept only so snapshots written before the request became the single source still restore.
     * Nothing produces it anymore and nothing reads it: {@link Execution#create} no longer fills
     * it, and the run resolves MCP servers from the request.</p>
     */
    @Deprecated(forRemoval = true)
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
                .agentId(agentId).agentRequest(request)
                .messages(new ArrayList<>(request.getMessages()))
                .executionState(ExecutionState.CREATED)
                .createAt(Instant.now()).build();
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

    // --- checked lifecycle -------------------------------------------------------------
    //
    // Each method below states the states it may be entered from and refuses everything else, so
    // an execution can never silently skip a step or leave a terminal state. They are the only
    // entry points ordinary runtime code should use.

    /** Starts a fresh execution. Only {@code CREATED} may start. */
    public void startChecked() {
        requireState(ExecutionState.CREATED);
        start();
    }

    /** Resumes a suspended execution. Only {@code SUSPENDED} may resume. */
    public void resumeChecked() {
        requireState(ExecutionState.SUSPENDED);
        resume();
    }

    /** Suspends a running execution, e.g. waiting on a tool. Only {@code RUNNING} may suspend. */
    public void suspendChecked() {
        requireState(ExecutionState.RUNNING);
        suspended();
    }

    /**
     * Completes a running execution.
     *
     * <p>Terminal states are final: an execution that already completed, failed or was cancelled
     * cannot complete again. Repairs that need to reconcile a stored outcome belong in
     * {@link #restoreTerminalState}, which is explicit about not being a transition.</p>
     */
    public void completeChecked() {
        requireState(ExecutionState.RUNNING);
        complete();
    }

    /**
     * Fails an execution that has not finished yet.
     *
     * <p>{@code CREATED} is allowed here because a run can fail before it ever starts — a rejected
     * request or a workspace that never opened never reaches {@code RUNNING}.</p>
     */
    public void failChecked(String errorMessage) {
        requireNotTerminal();
        fail(errorMessage);
    }

    /** Cancels an execution that has not finished yet, whether or not it ever started. */
    public void cancelChecked() {
        requireNotTerminal();
        cancel();
    }

    /**
     * Reconciles a decoded snapshot with an outcome that was committed while this process was not
     * looking — a run that was marked finished by a crash sweep, for instance.
     *
     * <p>This is deliberately <b>not</b> a state transition: the target state was already decided
     * and persisted elsewhere, so there is no state to legally transition from and no new moment to
     * record. Two consequences follow, and both matter:
     * <ul>
     *   <li>{@code completedAt} is taken from the caller when it supplies one, so the repair keeps
     *       the time that was actually committed instead of stamping {@code Instant.now()} over
     *       it. Passing {@code null} falls back to now for stores that carry no timestamp.</li>
     *   <li>{@code startAt} is left untouched. It records when the run first began and a resumed
     *       run must not lose it.</li>
     * </ul>
     *
     * <p>Repairs only ever move an execution <em>into</em> a terminal state, never out of one: an
     * execution that already holds a terminal state is returned unchanged rather than rewritten,
     * because two different terminal outcomes are not reconcilable and guessing would hide data.</p>
     *
     * @param terminalState the committed outcome; must itself be a terminal state
     * @param completedAt   the committed finish time, or {@code null} to stamp the current instant
     * @param errorMessage  failure detail, or {@code null} when the outcome carries none
     * @throws IllegalArgumentException if {@code terminalState} is not terminal
     */
    public void restoreTerminalState(ExecutionState terminalState, Instant completedAt, String errorMessage) {
        Objects.requireNonNull(terminalState, "terminalState");
        if (!terminalState.isTerminal()) {
            throw new IllegalArgumentException("Restore requires a terminal state: " + terminalState);
        }
        if (this.executionState != null && this.executionState.isTerminal()) {
            // Already terminal and disagreeing: leave the decoded outcome in place.
            return;
        }
        this.executionState = terminalState;
        this.completedAt = completedAt == null ? Instant.now() : completedAt;
        if (errorMessage != null) {
            this.errorMessage = errorMessage;
        }
    }

    /** Rejects the call unless the execution is currently in one of {@code allowed}. */
    private void requireState(ExecutionState... allowed) {
        ExecutionState current = Objects.requireNonNull(this.executionState,
                "execution.executionState");
        for (ExecutionState state : allowed) {
            if (current == state) {
                return;
            }
        }
        throw invalidTransitionFrom(current);
    }

    /** Rejects the call if the execution has already finished. */
    private void requireNotTerminal() {
        ExecutionState current = Objects.requireNonNull(this.executionState,
                "execution.executionState");
        if (current.isTerminal()) {
            throw invalidTransitionFrom(current);
        }
    }

    private IllegalStateException invalidTransitionFrom(ExecutionState current) {
        return new IllegalStateException(
                "Invalid execution transition from " + current + ": " + this.id);
    }

    public Map<String,Object> eventMetaData(){
        return agentRequest.runtimeParametersOrDefault().getEventMetaData();
    }

}
