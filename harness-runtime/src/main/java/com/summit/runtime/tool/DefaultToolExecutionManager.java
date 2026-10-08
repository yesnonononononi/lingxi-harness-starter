package com.summit.runtime.tool;

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

import java.util.*;
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
    private record ToolExecutionOutcome(ToolExecuteResult result, ToolCallStatus status) {}
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

    /**
     * Runs every requested call exactly once. The two branches are mutually exclusive on
     * {@link #canExecuteConcurrently}: a concurrent batch owns both the window it runs in parallel and
     * the overflow it runs serially, while the serial branch is reached only when nothing runs
     * concurrently. Gating the overflow on "the batch exceeds the concurrency limit" instead re-runs
     * the whole batch whenever the batch fits inside the limit — the concurrent half already consumed
     * it, so every tool would execute twice per request.
     *
     * <p>The limit is read only on the concurrent path: {@code concurrentToolLimit} is an optional
     * setting, and a serial batch must not be failed by unboxing a value it never consults.</p>
     */
    @Override
    public List<ToolExecuteResult> execute(ToolExecuteCommand toolExecuteCommand) {
        List<ToolCallRequest> requests = toolExecuteCommand.requests();
        List<ToolExecuteResult> result = new ArrayList<>();

        if (!canExecuteConcurrently(toolExecuteCommand)) {
            for (ToolCallRequest request : requests) {
                result.add(process(toolExecuteCommand, request));
            }
            return result;
        }

        int limit = Math.clamp(Objects.requireNonNullElse(toolExecutionContext.concurrentToolLimit(), requests.size()),
                1, requests.size());

        result.addAll(concurrentExecute(requests.subList(0, limit), toolExecuteCommand));

        // Empty when the batch fits inside the limit: the concurrent window already covered it.
        requests.subList(limit, requests.size()).forEach(request ->
                result.add(process(toolExecuteCommand, request)));

        return result;
    }


    private List<ToolExecuteResult> concurrentExecute(List<ToolCallRequest> requests, ToolExecuteCommand toolExecuteCommand) {
        List<CompletableFuture<ToolExecuteResult>> tasks = new ArrayList<>();
        for (ToolCallRequest request : requests) {
            CompletableFuture<ToolExecuteResult> f = CompletableFuture.supplyAsync(
                    () -> process(toolExecuteCommand, request), toolExecutor);

            tasks.add(f);
        }

        CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();

        return tasks.stream().map(CompletableFuture::join).toList();
    }


    /**
     * Concurrent execution admits read-only and isolated mutations with timeouts. A serial mutation or an unlimited timeout keeps the full batch serial.
     */
    private boolean canExecuteConcurrently(ToolExecuteCommand command) {
        return command.requests() != null && command.requests().size() > 1
                && command.requests().stream()
                .map(request -> command.resolve(toolExecutionContext.toolRegistry(), request.name()))
                .allMatch(tool -> tool != null && tool.allowConcurrent()
                        && tool.timeout() > 0
                );
    }


    public ToolExecuteResult process(ToolExecuteCommand toolExecuteCommand, ToolCallRequest request) {

        ToolDefinition<?> toolDef = toolExecuteCommand.resolve(
                this.toolExecutionContext.toolRegistry(), request.name());
        try {
            if (toolDef == null) {
                return publishEndEvent(toolExecuteCommand, request, null,
                        identified(ToolExecuteResult.err("Tool not found"),
                                request.id(),
                                null
                        ),
                        ToolCallStatus.REJECTED);
            }

            if (!admissible(toolDef, toolExecuteCommand)) {
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

            ToolExecutionOutcome outcome = this.executeTool(toolDef, toolExecution, toolExecuteCommand);

            result = identified(outcome.result(), request.id(), toolDef);

            return publishEndEvent(toolExecuteCommand, request, toolDef, result, outcome.status());

        } catch (Throwable e) {

            ToolExecuteResult err = ToolExecuteResult.err("Tool execution error" + e.getMessage());

            identified(err, request.id(), toolDef);

            publishEndEvent(toolExecuteCommand, request, toolDef, err, ToolCallStatus.FAILED);

            return err;
        }
    }

    /**
     * Whether a call is admitted under the run's whitelist: either the whitelist names the tool, or
     * the tool belongs to this request's own {@link com.summit.core.mcp.McpToolScope} — a discovered
     * name cannot be written into a list authored before the servers were contacted, so scope
     * membership stands in for that entry. A {@code null} or empty whitelist admits no static tool.
     */
    private boolean admissible(ToolDefinition<?> tool, ToolExecuteCommand command) {
        return this.toolExecutionContext.allowToolExecution(tool, command.allowedTools())
                || command.mcpToolScope().getTool(tool.name()) != null;
    }


    private Map<String, Object> mergeMetaData(Map<String, Object> metaData, Map<String, Object> toolMetaData) {
        Map<String, Object> newMapData = new HashMap<>(Map.copyOf(metaData));

        Objects.requireNonNullElse(toolMetaData, Map.<String, Object>of())
                .forEach(newMapData::putIfAbsent);

        return newMapData;
    }


    private ToolExecuteResult publishEndEvent(ToolExecuteCommand command,
                                              ToolCallRequest request,
                                              ToolDefinition<?> tool,
                                              ToolExecuteResult result,
                                              ToolCallStatus status
    ) {
        String toolName = tool == null ? request.name() : tool.name();
        toolExecutionContext.runtimeEventPublisher().onToolCallOutput(new ToolCallEndEvent(
                        request.id(),
                        command.executionId(),
                        command.responseId(),
                        toolName,
                        request.arguments(),
                        result.getToolOutput(),
                        mergeMetaData(command.eventMetaData(), result.getToolMetaData()),
                        status
                )
        );
        return result;
    }


    /**
     * Builds the per-call execution from its request. Workspace tools require a workspace;
     * host-side resource readers explicitly opt out through their executor contract.
     */
    private ToolExecution createToolExecution(@NonNull ToolCallRequest request, ToolDefinition<?> tool, ToolExecuteCommand command) {
        Workspace workspace = command.workspace();
        if (workspace == null && tool.executor().requiresWorkspace()) {
            throw new IllegalStateException(
                    "No workspace provided for tool '" + request.name() + "': AgentRequest.workspace is required");
        }
        return ToolExecution.builder()
                .id(request.id())
                .toolDefinition(tool)
                .executionId(command.executionId())
                .responseId(command.responseId())
                .turnId(command.executionId())
                .workspace(workspace)
                .skillConfig(command.skillConfig())
                .args(request.arguments())
                .attributes(command.attributes())
                .eventMetaData(command.eventMetaData())
                .allowOutsideWorkspace(command.allowOutsideWorkspace())
                .mcpToolScope(command.mcpToolScope())
                .build();
    }


    private ToolExecutionOutcome executeTool(ToolDefinition<?> toolDefinition,
                                             ToolExecution toolExecution,
                                             ToolExecuteCommand command
    ) throws Throwable {
        InvocationContext<ToolExecution> execute = InvocationContext.<ToolExecution>builder()
                .method(ToolExecutor.class.getMethod(
                        "execute", ToolExecution.class))
                .target(toolDefinition.executor())
                .context(toolExecution)
                .build();

        long timeoutSeconds = toolDefinition.timeout();
        if (timeoutSeconds <= 0) {
            ToolExecuteResult result = invokeTool(toolDefinition, toolExecution, command, execute);
            return completedOutcome(result);
        }
        Future<ToolExecuteResult> future = toolExecutor.submit(() -> {
            try {
                return invokeTool(toolDefinition, toolExecution, command, execute);
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
                                         ToolExecuteCommand command,
                                         InvocationContext<ToolExecution> invocation
    ) throws Throwable {

        toolExecutionContext.runtimeEventPublisher().onToolCall(new ToolCallStartEvent(
                toolExecution.getId(),
                toolExecution.getTurnId(),
                toolDefinition.name(),
                toolExecution.getArgs(),
                command.responseId(),
                command.eventMetaData()

        ));

        return (ToolExecuteResult) interceptorProcessor.proceed(invocation);
    }

    private static ToolExecutionOutcome completedOutcome(ToolExecuteResult result) {
        return new ToolExecutionOutcome(result, resultStatus(result, ToolCallStatus.FAILED));
    }

    private static ToolCallStatus resultStatus(ToolExecuteResult result, ToolCallStatus failureStatus) {
        if (result != null && result.isPromise()) return ToolCallStatus.PROMISED;
        return (result != null && result.isSuccess()) ? ToolCallStatus.COMPLETED : failureStatus;
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
