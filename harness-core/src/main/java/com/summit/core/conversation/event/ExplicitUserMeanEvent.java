package com.summit.core.conversation.event;

import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * The runtime asks the user for an explicit choice before the agent may continue
 * (the {@code require_choice} tool).
 *
 * <p>Mirrors {@link WaitCommandCheckEvent}: it carries everything the front-end needs
 * to render an interactive choice card (the tool execution id used to address the
 * decision endpoint, the question, the selectable options), while the agent-loop
 * thread is suspended on the {@code ChoiceDecideGate}.</p>
 *
 * <p>The options are a <em>recommendation</em>, not a whitelist: the user may always answer
 * with their own free-form text instead of picking one ({@link #isAllowCustomInput()} tells the
 * front-end to offer that extra input). Both answers are fed back to the model the same way.</p>
 */
@Data
public class ExplicitUserMeanEvent implements AgentEvent {
    private final String executionId;
    private final String toolExecutionId;
    private final String sessionId;
    private final String question;
    private final List<String> choices;

    /** Whether the user may answer with free-form text outside {@link #choices} (defaults to true). */
    private final boolean allowCustomInput;

    private final Instant createAt;

    /** Convenience constructor: custom free-form answers are allowed. */
    public ExplicitUserMeanEvent(String executionId, String toolExecutionId, String sessionId,
                                 String question, List<String> choices, Instant createAt) {
        this(executionId, toolExecutionId, sessionId, question, choices, true, createAt);
    }

    public ExplicitUserMeanEvent(String executionId, String toolExecutionId, String sessionId,
                                 String question, List<String> choices, boolean allowCustomInput,
                                 Instant createAt) {
        this.executionId = executionId;
        this.toolExecutionId = toolExecutionId;
        this.sessionId = sessionId;
        this.question = question;
        this.choices = choices;
        this.allowCustomInput = allowCustomInput;
        this.createAt = createAt;
    }

    @Override
    public String executionId() {
        return executionId;
    }

    @Override
    public Instant timestamp() {
        return createAt;
    }
}
