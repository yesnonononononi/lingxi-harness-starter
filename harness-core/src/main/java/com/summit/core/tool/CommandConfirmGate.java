package com.summit.core.tool;

import lombok.Getter;

import java.time.Instant;

/**
 * A "gate" for a single command awaiting human approval.
 *
 * <p>The tool execution thread never waits here for long: once the executor decides
 * that a command needs confirmation, it suspends the command right away (returns
 * {@link ToolResultType#CONFIRM_REQUIRED}). The actual wait happens at the agent
 * loop boundary ({@code ToolExecutionManager}), where this gate provides a
 * <b>bounded, interruptible</b> wait; a user/admin calls {@link #decide(CommandDecision)}
 * from any thread (e.g. an HTTP endpoint) to wake the waiter.</p>
 *
 * <p>The decision mechanics (pending / decide / await) are shared with the
 * plan-approval gate through {@link AbstractApprovalGate}.</p>
 */
@Getter
public class CommandConfirmGate extends AbstractApprovalGate {


    private final String toolExecutionId;
    private final String command;
    private final Instant createdAt = Instant.now();

    public CommandConfirmGate(String toolExecutionId, String command) {
        this.toolExecutionId = toolExecutionId;
        this.command = command;
    }

}
