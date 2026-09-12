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
 */
@Data
public class ExplicitUserMeanEvent implements AgentEvent {
    private final String executionId;
    private final String toolExecutionId;
    private final String sessionId;
    private final String question;
    private final List<String> choices;
    private final Instant createAt;

    @Override
    public String executionId() {
        return executionId;
    }

    @Override
    public Instant timestamp() {
        return createAt;
    }
}
