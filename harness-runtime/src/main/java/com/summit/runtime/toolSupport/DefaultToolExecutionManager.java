package com.summit.runtime.toolSupport;

import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.event.ToolCallEndEvent;
import com.summit.core.conversation.event.ToolCallStartEvent;
import com.summit.core.interceptor.InterceptorProcessor;
import com.summit.core.interceptor.InvocationContext;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.tool.*;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;


import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Getter
public class DefaultToolExecutionManager implements ToolExecutionManager, AutoCloseable {
    private final ToolExecutionContext toolExecutionContext;
    private final InterceptorProcessor<ToolExecution> interceptorProcessor;
    private final List<ToolExecutionPolicy> executionPolicies;
    /**
     * Keep blocking tools away from the JVM-wide common pool. Virtual threads make command and
     * network I/O cheap without imposing a permanent platform-thread pool.
     */
    private final ExecutorService toolExecutor = Executors.newVirtualThreadPerTaskExecutor();


    public DefaultToolExecutionManager(ToolExecutionContext toolExecutionContext,
                                       InterceptorProcessor<ToolExecution> interceptorProcessor,
                                       List<ToolExecutionPolicy> executionPolicies) {
        this.toolExecutionContext = toolExecutionContext;
        this.interceptorProcessor = interceptorProcessor;
        this.executionPolicies = executionPolicies == null ? List.of() : executionPolicies.stream()
                .sorted(java.util.Comparator.comparingInt(ToolExecutionPolicy::order)).toList();
    }

    @Override
    public ToolRegistry toolRegistry() {
        return this.toolExecutionContext.toolRegistry();
    }


    @Override
    public void close() {
        toolExecutor.shutdownNow();
    }

    @Override
    public List<ToolExecuteResult> execute(ToolExecuteCommand toolExecuteCommand) {
        List<ToolExecuteResult> result = new ArrayList<>();

        List<CompletableFuture<ToolExecuteResult>> tasks = new ArrayList<>();
        if (canExecuteConcurrently(toolExecuteCommand)) {

            for (ToolCallRequest request : toolExecuteCommand.requests()) {

                CompletableFuture<ToolExecuteResult> f = CompletableFuture.supplyAsync(
                        () -> process(toolExecuteCommand, request), toolExecutor);
                tasks.add(f);
            }

            CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();

            return tasks.stream().map(CompletableFuture::join).toList();

        } else {
            for (ToolCallRequest request : toolExecuteCommand.requests()) {
                result.add(process(toolExecuteCommand, request));
            }
        }

        return result;

    }

    /**
     * Concurrent execution is deliberately limited to an entirely read-only batch. A mixed batch is kept serial so a read cannot race a workspace mutation and multiple writes retain the order chosen by the model.
     */
    private boolean canExecuteConcurrently(ToolExecuteCommand command) {
        return command.requests() != null && command.requests().size() > 1
                && command.requests().stream()
                .map(request -> toolExecutionContext.toolRegistry().getTool(request.name()))
                .allMatch(tool -> tool != null && tool.allowConcurrent()
                        && tool.timeout() > 0
                );
    }


    public ToolExecuteResult process(ToolExecuteCommand toolExecuteCommand, ToolCallRequest request) {

        ToolDefinition<?> toolDef = this.toolExecutionContext.toolRegistry().getTool(request.name());
        try {
            if (toolDef == null) {
                return publishEndEvent(toolExecuteCommand, request, null,
                        identified(ToolExecuteResult.err("Tool not found"),
                                request.id(),
                                null
                        ),
                        ToolCallStatus.REJECTED);
            }

            if (!this.toolExecutionContext.allowToolExecution(toolDef, toolExecuteCommand.allowedTools())) {
                return publishEndEvent(toolExecuteCommand, request, toolDef, identified(ToolExecuteResult.err(
                                "Tool '" + toolDef.name() + "' is not allowed for this agent request: "
                                        + "it is not part of the tool set this run is confined to"),
                        request.id(), toolDef), ToolCallStatus.REJECTED);
            }

            ToolExecution toolExecution = createToolExecution(request, toolDef, toolExecuteCommand);

            ToolExecuteResult result = applyPolicies(toolExecution);

            if (result != null) { // tool has not been conducted
                result = identified(result, request.id(), toolDef);
                ToolCallStatus status = resultStatus(result, ToolCallStatus.REJECTED);
                return publishEndEvent(toolExecuteCommand, request, toolDef, result, status);
            }

            ToolExecutionOutcome outcome = this.executeTool(toolDef, toolExecution);

            result = identified(outcome.result(), request.id(), toolDef);

            return publishEndEvent(toolExecuteCommand, request, toolDef, result, outcome.status());

        } catch (Throwable e) {
            this.toolExecutionContext.runtimeEventPublisher().onToolCallOutput(
                    new ToolCallEndEvent(request.id(), toolExecuteCommand.executionId(),
                            toolDef == null ? request.name() : toolDef.name(),
                            request.arguments(), "Tool execution error" + e.getMessage(), ToolCallStatus.FAILED)

            );
            return identified(ToolExecuteResult.err("Tool execution error" + e.getMessage()), request.id(), toolDef);

        }
    }

    private ToolExecuteResult publishEndEvent(ToolExecuteCommand command, ToolCallRequest request,
                                              ToolDefinition<?> tool, ToolExecuteResult result,
                                              ToolCallStatus status) {
        String toolName = tool == null ? request.name() : tool.name();
        toolExecutionContext.runtimeEventPublisher().onToolCallOutput(new ToolCallEndEvent(
                        request.id(),
                        command.executionId(), toolName, request.arguments(),
                        result.getToolOutput(),
                        status
                )
        );
        return result;
    }


    /**
     * Builds the per-call execution. The workspace ALWAYS comes from the originating {@link ToolExecuteCommand} (i.e. the {@code AgentRequest}); a missing workspace is a programming error and fails the call.
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
                .executionId(command.executionId())
                .turnId(command.executionId())
                .workspace(workspace)
                .args(request.arguments())
                .attributes(command.attributes())
                .allowOutsideWorkspace(command.allowOutsideWorkspace())
                .build();
    }


    private ToolExecutionOutcome executeTool(ToolDefinition<?> toolDefinition,
                                             ToolExecution toolExecution) throws Throwable {
        InvocationContext<ToolExecution> execute = InvocationContext.<ToolExecution>builder()
                .method(ToolExecutor.class.getMethod(
                        "execute", ToolExecution.class))
                .target(toolDefinition.executor())
                .context(toolExecution)
                .build();

        long timeoutSeconds = toolDefinition.timeout();
        if (timeoutSeconds <= 0) {
            ToolExecuteResult result = invokeTool(toolDefinition, toolExecution, execute);
            return completedOutcome(result);
        }
        Future<ToolExecuteResult> future = toolExecutor.submit(() -> {
            try {
                return invokeTool(toolDefinition, toolExecution, execute);
            } catch (Throwable e) {
                throw new CompletionException(e);
            }
        });

        try {
            return completedOutcome(future.get(timeoutSeconds, TimeUnit.SECONDS));

        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("tool [{}] execution timed out after {}s, returning error to model",
                    toolDefinition.name(), timeoutSeconds);
            return new ToolExecutionOutcome(ToolExecuteResult.err(
                    "tool execution timeout after " + timeoutSeconds + "s"), ToolCallStatus.TIMED_OUT);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return new ToolExecutionOutcome(
                    ToolExecuteResult.err("tool execution interrupted"), ToolCallStatus.CANCELLED);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof CompletionException completion && completion.getCause() != null) {
                cause = completion.getCause();
            }
            throw cause;
        }
    }

    private ToolExecuteResult invokeTool(ToolDefinition<?> toolDefinition,
                                         ToolExecution toolExecution,
                                         InvocationContext<ToolExecution> invocation) throws Throwable {

        toolExecutionContext.runtimeEventPublisher().onToolCall(new ToolCallStartEvent(
                toolExecution.getId(), toolExecution.getTurnId(),
                toolDefinition.name(), toolExecution.getArgs()));

        return (ToolExecuteResult) interceptorProcessor.proceed(invocation);
    }

    private static ToolExecutionOutcome completedOutcome(ToolExecuteResult result) {
        return new ToolExecutionOutcome(result, resultStatus(result, ToolCallStatus.FAILED));
    }

    private static ToolCallStatus resultStatus(ToolExecuteResult result, ToolCallStatus failureStatus) {
        if (result != null && result.isPromise()) return ToolCallStatus.PROMISED;
        return isSuccessful(result) ? ToolCallStatus.COMPLETED : failureStatus;
    }

    private static boolean isSuccessful(ToolExecuteResult result) {
        return result != null && result.isSuccess();
    }

    private record ToolExecutionOutcome(ToolExecuteResult result, ToolCallStatus status) {
    }


    /**
     * Stamps the call identity onto a result: executors report their output only, while the conversation needs the tool-call id and the definition to build a tool message that answers the right call — the manager is where both are known.
     */
    private static @NonNull ToolExecuteResult identified(ToolExecuteResult result, String id, ToolDefinition<?> toolDefinition) {
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
