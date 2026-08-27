package com.summit.runtime.tool;

import com.summit.harnesscore.conversation.event.ToolCallEndEvent;
import com.summit.harnesscore.conversation.event.ToolCallStartEvent;
import com.summit.harnesscore.interceptor.InterceptorProcessor;
import com.summit.harnesscore.interceptor.InvocationContext;
import com.summit.harnesscore.tool.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
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
    private final ToolExecutionContext toolExecutionContext;
    private final InterceptorProcessor<ToolInterceptor, ToolExecution> interceptorProcessor;
    private final CommonToolConfig commonToolConfig;


    @Override
    public List<ToolExecuteResult> execute(ToolExecuteCommand toolExecuteCommand) {

        return toolExecuteCommand.requests().stream()
                .map(request -> {
                    try {
                        this.toolExecutionContext.runtimeEventPublisher().onToolCall(new ToolCallStartEvent(toolExecuteCommand.executionId(), request.name(), request.arguments()));

                        ToolDefinition<?> toolDef = this.toolExecutionContext.toolRegistry().getTool(request.name());

                        if (toolDef == null) {
                            return ToolExecuteResult.err(request.id(), null, "Tool not found");
                        }
                        ToolExecuteResult result = this.executeTool(toolDef, createToolExecution(request, toolDef));

                        this.toolExecutionContext.runtimeEventPublisher().onToolCallOutput(new ToolCallEndEvent(toolExecuteCommand.executionId(),toolDef.toolSpecification().name(), request.arguments(), formatEventToolOutput(result.getToolOutput())));

                        return result;

                    } catch (Throwable e) {
                        // keep the tool name in the error result so the model can tell which tool failed
                        ToolDefinition<?> toolDef = this.toolExecutionContext.toolRegistry().getTool(request.name());
                        this.toolExecutionContext.runtimeEventPublisher().onToolCallOutput(new ToolCallEndEvent(toolExecuteCommand.executionId(), toolDef.toolSpecification().name(), request.arguments(), "Tool execution error" + e.getMessage()));
                        return ToolExecuteResult.err(request.id(), toolDef.toolSpecification(), "Tool execution error" + e.getMessage());
                    }
                })

                .toList();
    }


    @Override
    public ToolRegistry toolRegistry() {
        return this.toolExecutionContext.toolRegistry();
    }

    private ToolExecution createToolExecution(@NonNull ToolExecutionRequest request, ToolDefinition<?> tool) {
        return ToolExecution.builder()
                .id(request.id())
                .toolDefinition(tool)
                .workspace(this.toolExecutionContext.workspace())
                .args(request.arguments())
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
        long timeoutSeconds = toolDefinition.timeout() == null ? 0 : toolDefinition.timeout();
        if (timeoutSeconds <= 0) {
            return (ToolExecuteResult) this.interceptorProcessor.proceed(execute);
        }
        // run the executor asynchronously so a hung tool (e.g. read on a slow path) cannot
        // block the agent loop forever; timeout only releases the caller, the worker may still
        // finish on its own in the background.
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
                    toolDefinition.toolSpecification().name(), timeoutSeconds);
            return ToolExecuteResult.err(toolExecution.getId(), toolExecution.getToolDefinition().toolSpecification(),
                    "tool execution timeout after " + timeoutSeconds + "s");
        }
    }

}
