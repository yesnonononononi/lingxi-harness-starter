package com.summit.tools.terminal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.event.WaitCommandCheckEvent;
import com.summit.core.interceptor.InvocationContext;
import com.summit.core.runtime.ShellType;
import com.summit.core.tool.CommandConfirmGate;
import com.summit.core.tool.CommandDecision;
import com.summit.core.tool.CommandConfirmLevel;
import com.summit.core.tool.CommandConfirmRegistry;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecution;
import com.summit.core.tool.ToolInterceptor;
import com.summit.core.tool.ToolResultType;
import com.summit.tools.arguments.ExecuteCommandRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Human-approval interceptor for the command tool (execute_command).
 *
 * <p>Using the {@link #preDecide} short-circuit of the generic interceptor chain,
 * it decides the call <b>before</b> the command actually runs:
 * <ul>
 *   <li>no confirmation needed / already approved → returns {@code null} and lets
 *       the command executor run as usual;</li>
 *   <li>confirmation needed and still undecided → registers a gate, broadcasts a
 *       {@link WaitCommandCheckEvent} and short-circuits with
 *       {@link ToolResultType#CONFIRM_REQUIRED}; {@code ToolExecutionManager} then
 *       waits at the agent loop boundary, with a bounded timeout, for the
 *       user/admin decision;</li>
 *   <li>already rejected → short-circuits with a rejection result and the command
 *       never runs.</li>
 * </ul>
 *
 * <p>The adjudication is decoupled from the concrete executor: the approval policy
 * is injected into the generic interceptor chain as a pluggable
 * {@link ToolInterceptor}; any other tool that needs human confirmation only has
 * to provide its own adjudicator.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class CommandApprovalToolInterceptor implements ToolInterceptor {
    private final ObjectMapper objectMapper;
    private final RuntimeEventPublisher eventPublisher;
    private final CommandConfirmRegistry confirmRegistry;

    @Override
    public Object preDecide(InvocationContext<ToolExecution> invocationContext) {
        ToolExecution toolExecution = invocationContext.getContext();
        ToolDefinition<?> toolDefinition = toolExecution.getToolDefinition();
        // Only adjudicate the command tool; all other tools pass through
        if (toolDefinition == null || !(toolDefinition.executor() instanceof CommandToolDefinitionExecutor)) {
            return null;
        }
        try {
            ExecuteCommandRequest request = resolveArgs(toolExecution);
            String command = request.getCommand();
            // A blank command is reported by the executor itself; no approval needed
            if (command == null || command.isBlank()) {
                return null;
            }
            if (!requiresConfirmation(toolExecution.getCommandConfirmLevel(), command, shellTypeOf(toolExecution))) {
                return null; // allow through
            }
            return suspendOrResolve(toolExecution, command);
        } catch (JsonProcessingException e) {
            // Unparsable arguments: do not adjudicate; let the executor report the error to the model
            return null;
        }
    }

    @Override
    public void pre(InvocationContext<ToolExecution> invocationContext) {

    }

    @Override
    public void after(InvocationContext<ToolExecution> invocationContext, Object result) {

    }

    /**
     * Whether the command needs human approval under the current confirmation level.
     */
    private boolean requiresConfirmation(CommandConfirmLevel level, String command, ShellType shellType) {
        if (level == null) {
            return false;
        }
        return switch (level) {
            case FULL_ACCESS -> false;
            case PRE_EXEC_CONFIRM -> true; // every command needs confirmation before execution
            case DANGEROUS_BLOCK -> !CommandGuard.isAllowed(command, shellType); // only block dangerous commands
        };
    }

    private ShellType shellTypeOf(ToolExecution toolExecution) {
        if (toolExecution.getWorkspace() == null
                || toolExecution.getWorkspace().runtimeEnvironment() == null) {
            return null;
        }
        return toolExecution.getWorkspace().runtimeEnvironment().shellType();
    }

    /**
     * Suspends the command while it is undecided: registers a gate, broadcasts a
     * wait-for-approval event and returns {@link ToolResultType#CONFIRM_REQUIRED}
     * so the execution manager waits for the decision at the loop boundary; when
     * already rejected, returns the rejection result directly; when already
     * approved, lets it through (returns {@code null}).
     *
     * @return the result to short-circuit back to the caller; {@code null} means
     *         approved, so execution should proceed
     */
    private ToolExecuteResult suspendOrResolve(ToolExecution toolExecution, String command) {
        String toolExecutionId = toolExecution.getId();
        CommandConfirmGate gate = confirmRegistry.get(toolExecutionId);
        if (gate == null) {
            confirmRegistry.register(toolExecutionId, command);
            publishWaitCheck(toolExecution, command);

            log.info("【confirm】 command suspended for approval, toolExecution={}, command={}", toolExecutionId, command);
            return ToolExecuteResult.err(toolExecution.getId(), toolExecution.getToolDefinition(),
                    "command is waiting for human approval; it will run after approval or be skipped if rejected: " + command,
                    ToolResultType.CONFIRM_REQUIRED);
        }
        CommandDecision decision = gate.getDecision();

        if (decision == null) {
            // Still PENDING but scheduled again (abnormal path): broadcast idempotently and suspend
            publishWaitCheck(toolExecution, command);
            return ToolExecuteResult.err(toolExecution.getId(), toolExecution.getToolDefinition(),
                    "command is still waiting for human approval: " + command,
                    ToolResultType.CONFIRM_REQUIRED);
        }

        if (decision == CommandDecision.REJECT) {
            log.info("【confirm】 command rejected, toolExecution={}", toolExecutionId);
            return ToolExecuteResult.err(toolExecution.getId(), toolExecution.getToolDefinition(),
                    "command was rejected by the user and NOT executed: " + command);
        }

        // APPROVED: allow through; the command executor will actually run it
        return null;
    }

    private void publishWaitCheck(ToolExecution toolExecution, String command) {
        this.eventPublisher.onCommandCheck(new WaitCommandCheckEvent(
                toolExecution.getTurnId(),
                toolExecution.getId(),
                String.valueOf(toolExecution.getSessionId()),
                command
        ));
    }

    private ExecuteCommandRequest resolveArgs(ToolExecution toolExecution) throws JsonProcessingException {
        return objectMapper.readValue(toolExecution.getArgs(), ExecuteCommandRequest.class);
    }
}
