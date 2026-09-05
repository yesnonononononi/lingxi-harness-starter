package com.summit.runtime.tool;

import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.event.ToolCallEndEvent;
import com.summit.core.conversation.event.ToolCallStartEvent;
import com.summit.core.interceptor.InterceptorProcessor;
import com.summit.core.interceptor.InvocationContext;
import com.summit.core.runtime.Workspace;
import com.summit.core.tool.*;
import com.summit.runtime.configs.CommonToolConfig;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@AllArgsConstructor
@Getter
public class DefaultToolExecutionManager implements ToolExecutionManager {
    /** Max time to wait for human approval of a command; a timeout is treated as a rejection (prevents a session from holding the agent loop thread forever). */
    private static final long CONFIRM_AWAIT_TIMEOUT_SECONDS = 300L;

    private final ToolExecutionContext toolExecutionContext;
    private final InterceptorProcessor<ToolExecution> interceptorProcessor;
    private final CommonToolConfig commonToolConfig;
    private final CommandConfirmRegistry commandConfirmRegistry;


    @Override
    public List<ToolExecuteResult> execute(ToolExecuteCommand toolExecuteCommand) {

        return toolExecuteCommand.requests().stream()
                .map(request -> {
                    try {
                        this.toolExecutionContext.runtimeEventPublisher().onToolCall(new ToolCallStartEvent(toolExecuteCommand.executionId(), toolExecuteCommand.sessionId(), request.name(), request.arguments()));

                        ToolDefinition<?> toolDef = this.toolExecutionContext.toolRegistry().getTool(request.name());

                        if (toolDef == null) {
                            return ToolExecuteResult.err(request.id(), null, "Tool not found");
                        }
                        if (!this.toolExecutionContext.allowToolExecution(toolDef, toolExecuteCommand.loopBoundary())) {
                            return ToolExecuteResult.err(request.id(), toolDef,
                                    "Tool '" + toolDef.name() + "' is not allowed in the current loop boundary: "
                                            + "read-only boundary (PLANNING) only permits read-only tools");
                        }
                        ToolExecution toolExecution = createToolExecution(request, toolDef, toolExecuteCommand);

                        ToolExecuteResult result = this.executeTool(toolDef, toolExecution);


                        result = awaitConfirmationIfNeeded(toolDef, toolExecution, result);

                        this.toolExecutionContext.runtimeEventPublisher().onToolCallOutput(new ToolCallEndEvent(toolExecuteCommand.executionId(),toolDef.name(), request.arguments(),toolExecuteCommand.sessionId() ,formatEventToolOutput(result.getToolOutput())));

                        return result;

                    } catch (Throwable e) {
                        // keep the tool name in the error result so the model can tell which tool failed
                        ToolDefinition<?> toolDef = this.toolExecutionContext.toolRegistry().getTool(request.name());
                        this.toolExecutionContext.runtimeEventPublisher().onToolCallOutput(new ToolCallEndEvent(toolExecuteCommand.executionId(), toolDef == null ? request.name() : toolDef.name(), request.arguments(),toolExecuteCommand.sessionId(), "Tool execution error" + e.getMessage()));
                        return ToolExecuteResult.err(request.id(), toolDef, "Tool execution error" + e.getMessage());
                    }
                })

                .toList();
    }


    @Override
    public ToolRegistry toolRegistry() {
        return this.toolExecutionContext.toolRegistry();
    }










    /**
     * Builds the per-call execution. The workspace ALWAYS comes from the
     * originating {@link ToolExecuteCommand} (i.e. the {@code AgentRequest});
     * a missing workspace is a programming error and fails the call.
     */
    private ToolExecution createToolExecution(@NonNull ToolCallRequest request, ToolDefinition<?> tool, ToolExecuteCommand command) {
        Workspace workspace = command.workspace();
        if (workspace == null) {
            throw new IllegalStateException(
                    "No workspace provided for tool '" + request.name() + "': AgentRequest.workspace is required");
        }
        return ToolExecution.builder()
                .id(request.id())
                .toolDefinition(tool)
                .sessionId(command.sessionId())
                .turnId(command.executionId())
                .workspace(workspace)
                .args(request.arguments())
                .commandConfirmLevel(command.commandConfirmLevel())
                .loopBoundary(command.loopBoundary())
                .build();
    }

    /**
     * Format the tool output to be displayed in the event.
     *
     * @param output The tool output to be formatted.
     * @return The formatted tool output.
     */
    private String formatEventToolOutput(String output) {
        Integer maxChar = commonToolConfig.maxToolOutputDisplay();
        return output.length() > maxChar ? output.substring(0, maxChar) + "..." : output;
    }

    private ToolExecuteResult executeTool(ToolDefinition<?> toolDefinition, ToolExecution toolExecution) throws Throwable {
        InvocationContext<ToolExecution> execute = InvocationContext.<ToolExecution>builder()
                .method(ToolExecutor.class.getMethod(
                        "execute", ToolExecution.class))
                .target(toolDefinition.executor())
                .context(toolExecution)
                .build();
        long timeoutSeconds = toolDefinition.timeout();
        if (timeoutSeconds <= 0) {
            return (ToolExecuteResult) this.interceptorProcessor.proceed(execute);
        }

        CompletableFuture<ToolExecuteResult> future = CompletableFuture.supplyAsync(() -> {
            try {
                return (ToolExecuteResult) this.interceptorProcessor.proceed(execute);
            } catch (Throwable e) {
                throw new CompletionException(e);
            }
        });
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("tool [{}] execution timed out after {}s, returning error to model",
                    toolDefinition.name(), timeoutSeconds);
            return ToolExecuteResult.err(toolExecution.getId(), toolExecution.getToolDefinition(),
                    "tool execution timeout after " + timeoutSeconds + "s");
        }
    }

    /**
     * After the executor suspends a command (returns
     * {@link ToolResultType#CONFIRM_REQUIRED}), waits on the agent loop thread
     * for the user/admin approval decision:
     *
     * <ul>
     *   <li>APPROVE: re-executes with the same {@link ToolExecution} — the executor
     *       sees the gate is approved and actually runs the command;</li>
     *   <li>REJECT: returns a rejection error;</li>
     *   <li>timeout: treated as a rejection so the loop thread is never held forever;</li>
     *   <li>thread interruption (e.g. /stop cancellation): restores the interrupt
     *       flag and returns, letting the loop reach its checkpoint and consume the
     *       STOP command to complete the cancellation.</li>
     * </ul>
     *
     * <p>The wait does not happen on the tool thread, so it is not cut short by the
     * {@code ToolDefinition.timeout()} execution timeout; the gate is always removed
     * once the wait finishes, so nothing leaks.</p>
     */
    private ToolExecuteResult awaitConfirmationIfNeeded(ToolDefinition<?> toolDefinition, ToolExecution toolExecution, ToolExecuteResult result) throws Throwable {
        if (result == null || result.getToolResultType() != ToolResultType.CONFIRM_REQUIRED) {
            return result;
        }
        String toolExecutionId = toolExecution.getId();
        CommandConfirmGate gate = commandConfirmRegistry.get(toolExecutionId);
        if (gate == null) {
            return result;
        }
        log.info("【confirm】 waiting for approval of toolExecution={}, command={}", toolExecutionId, gate.getCommand());

        CommandDecision decision;
        try {
            decision = gate.awaitDecision(CONFIRM_AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            commandConfirmRegistry.unregister(toolExecutionId);
            log.warn("【confirm】 interrupted while waiting for approval, toolExecution={}", toolExecutionId);
            return ToolExecuteResult.err(toolExecutionId, toolDefinition,
                    "waiting for command approval was interrupted, command was NOT executed: " + gate.getCommand());
        }
        if (decision == null) {
            commandConfirmRegistry.unregister(toolExecutionId);
            log.warn("【confirm】 approval timed out after {}s, toolExecution={}", CONFIRM_AWAIT_TIMEOUT_SECONDS, toolExecutionId);
            return ToolExecuteResult.err(toolExecutionId, toolDefinition,
                    "command approval timed out after " + CONFIRM_AWAIT_TIMEOUT_SECONDS + "s, command was NOT executed: " + gate.getCommand());
        }
        if (decision == CommandDecision.REJECT) {
            commandConfirmRegistry.unregister(toolExecutionId);
            log.info("【confirm】 command rejected by user, toolExecution={}", toolExecutionId);
            return ToolExecuteResult.err(toolExecutionId, toolDefinition,
                    "command was rejected by the user and NOT executed: " + gate.getCommand());
        }

        // APPROVE: re-execute — the executor sees the gate is approved and actually runs the command
        log.info("【confirm】 command approved, re-executing toolExecution={}", toolExecutionId);
        try {
            return this.executeTool(toolDefinition, toolExecution);
        } finally {
            commandConfirmRegistry.unregister(toolExecutionId);
        }
    }

}
