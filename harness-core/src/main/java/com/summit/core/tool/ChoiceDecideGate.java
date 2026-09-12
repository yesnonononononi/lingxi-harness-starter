package com.summit.core.tool;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;
import java.util.List;

/**
 * A "gate" for a requiring-choice tool ({@code require_choice}) awaiting an explicit
 * user selection.
 *
 * <p>Same human-in-the-loop mechanics as {@link CommandConfirmGate}: the tool execution
 * thread never blocks inside the executor — once the model asks the user, the executor
 * suspends the call (returns {@link ToolResultType#CHOICE_REQUIRED}); the actual bounded,
 * interruptible wait happens at the agent-loop boundary
 * ({@code DefaultToolExecutionManager}); any thread (e.g. an HTTP endpoint) calls
 * {@link #decide(String)} with the selected option to wake the waiter.</p>
 */
@Getter
@AllArgsConstructor
public class ChoiceDecideGate extends AbstractApprovalGate<String>{
    public record Choice(
            String question,
            List<String> choices
    ){
    }
    private final String toolExecutionId;
    private final Choice choice;
    private final Instant createdAt = Instant.now();
}
