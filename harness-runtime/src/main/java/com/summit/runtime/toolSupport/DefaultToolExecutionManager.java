package com.summit.runtime.toolSupport;

import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.event.ToolCallEndEvent;
import com.summit.core.conversation.event.ToolCallStartEvent;
import com.summit.core.interceptor.InterceptorProcessor;
import com.summit.core.interceptor.InvocationContext;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.tool.*;
import com.summit.runtime.configs.CommonToolConfig;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;


import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Getter
public class DefaultToolExecutionManager implements ToolExecutionManager {
    private final ToolExecutionContext toolExecutionContext;
    private final InterceptorProcessor<ToolExecution> interceptorProcessor;
    private final CommonToolConfig commonToolConfig;
    private final List<ToolExecutionPolicy> executionPolicies;

    public DefaultToolExecutionManager(ToolExecutionContext toolExecutionContext,
                                       InterceptorProcessor<ToolExecution> interceptorProcessor,
                                       CommonToolConfig commonToolConfig) {
        this(toolExecutionContext, interceptorProcessor, commonToolConfig, List.of());
    }

    public DefaultToolExecutionManager(ToolExecutionContext toolExecutionContext,
                                       InterceptorProcessor<ToolExecution> interceptorProcessor,
                                       CommonToolConfig commonToolConfig,
                                       List<ToolExecutionPolicy> executionPolicies) {
        this.toolExecutionContext = toolExecutionContext;
        this.interceptorProcessor = interceptorProcessor;
        this.commonToolConfig = commonToolConfig;
        this.executionPolicies = executionPolicies == null ? List.of() : executionPolicies.stream()
                .sorted(java.util.Comparator.comparingInt(ToolExecutionPolicy::order)).toList();
    }


    @Override
    public List<ToolExecuteResult> execute(ToolExecuteCommand toolExecuteCommand) {

        return toolExecuteCommand.requests().stream()
                .map(request -> {
                    try {
                        this.toolExecutionContext.runtimeEventPublisher().onToolCall(new ToolCallStartEvent(toolExecuteCommand.executionId(), toolExecuteCommand.sessionId(), request.name(), request.arguments()));

                        ToolDefinition<?> toolDef = this.toolExecutionContext.toolRegistry().getTool(request.name());

                        if (toolDef == null) {
                            return identified(ToolExecuteResult.err("Tool not found"), request.id(), null);
                        }
                        if (!toolDef.allowedFor(toolExecuteCommand.allowedTools())) {
                            return identified(ToolExecuteResult.err(
                                    "Tool '" + toolDef.name() + "' is not allowed for this agent request"),
                                    request.id(), toolDef);
                        }
                        if (!this.toolExecutionContext.allowToolExecution(toolDef, toolExecuteCommand.loopBoundary())) {
                            return identified(ToolExecuteResult.err(
                                    "Tool '" + toolDef.name() + "' is not allowed in the current loop boundary: "
                                            + "read-only boundary (PLANNING) only permits read-only tools"),
                                    request.id(), toolDef);
                        }
                        ToolExecution toolExecution = createToolExecution(request, toolDef, toolExecuteCommand);

                        ToolExecuteResult result = applyPolicies(toolExecution);
                        if (result == null) {
                            result = this.executeTool(toolDef, toolExecution);
                        }
                        result = identified(result, request.id(), toolDef);

                        this.toolExecutionContext.runtimeEventPublisher().onToolCallOutput(new ToolCallEndEvent(toolExecuteCommand.executionId(), toolDef.name(), request.arguments(), toolExecuteCommand.sessionId(), formatEventToolOutput(result.getToolOutput())));

                        return result;

                    } catch (Throwable e) {
                        // keep the tool name in the error result so the model can tell which tool failed
                        ToolDefinition<?> toolDef = this.toolExecutionContext.toolRegistry().getTool(request.name());
                        this.toolExecutionContext.runtimeEventPublisher().onToolCallOutput(new ToolCallEndEvent(toolExecuteCommand.executionId(), toolDef == null ? request.name() : toolDef.name(), request.arguments(), toolExecuteCommand.sessionId(), "Tool execution error" + e.getMessage()));
                        return identified(ToolExecuteResult.err("Tool execution error" + e.getMessage()), request.id(), toolDef);
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
            return ToolExecuteResult.err(
                    "tool execution timeout after " + timeoutSeconds + "s");
        }
    }

    /**
     * Stamps the call identity onto a result: executors report their output only, while the
     * conversation needs the tool-call id and the definition to build a tool message that answers
     * the right call — the manager is where both are known.
     */
    private static ToolExecuteResult identified(ToolExecuteResult result, String id, ToolDefinition<?> toolDefinition) {
        ToolExecuteResult identified = result != null
                ? result
                : ToolExecuteResult.err("tool executor returned no result");
        identified.setId(id);
        identified.setToolSpecification(toolDefinition);
        return identified;
    }

    private ToolExecuteResult applyPolicies(ToolExecution execution) {
        for (ToolExecutionPolicy policy : executionPolicies) {
            ToolExecuteResult result = policy.beforeExecution(execution);
            if (result != null) return result;
        }
        return null;
    }

}
