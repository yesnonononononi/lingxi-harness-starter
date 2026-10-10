# LingXi Harness Agent

An extensible execution framework for building Java AI agents. It provides model invocation, the agentic loop, tool execution, context management, MCP, Skills, workspace, runtime events, and execution control interfaces. Applications supply model configuration, tool permissions, approval rules, and persistence through requests and SPIs.

## Table of Contents

- [Quick Start](#quick-start)
- [Workspace](#workspace)
- [Tool Registration and Permissions](#tool-registration-and-permissions)
- [Tool Concurrency Policy and Execution Behavior](#tool-concurrency-policy-and-execution-behavior)
- [Human Interaction and Approval Resume](#human-interaction-and-approval-resume)
- [Intercepting the Loop Lifecycle](#intercepting-the-loop-lifecycle)
- [Runtime Events and Persistence](#runtime-events-and-persistence)
- [MCP and Skills](#mcp-and-skills)
- [Context Compaction](#context-compaction)
- [SPI and Extension Contract Reference](#spi-and-extension-contract-reference)

## Quick Start

Requires Java 21. Add the starter to a Spring Boot application:

```xml
<dependency>
    <groupId>io.github.yesnonononononi</groupId>
    <artifactId>lingxi-harness-spring-boot-starter</artifactId>
    <version>1.1.3</version>
</dependency>
```

Configure an OpenAI-compatible endpoint in `application.yaml`:

```yaml
lingxi:
  agent:
    model:
      conf:
        chat:
          base-url: https://api.example.com/v1
          api-key: ${MODEL_API_KEY}
          model-name: your-model
          timeout: 60s
          max-iterations: 50
          max-tokens: 102400
```

Inject `DefaultChatAgent` and submit the full context:

```java
import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.runtime.agent.DefaultChatAgent;
import java.util.List;

// agent is the Spring-injected DefaultChatAgent.
Execution execution = agent.execute(AgentRequest.builder()
        .messages(List.of(UserMessageEntity.from("Introduce this project")))
        .systemPrompt("You are a project assistant. Answer based on facts.")
        .build());
```

`execute` is a synchronous call. When it returns, the execution has completed, been suspended, or been cancelled; a failed execution throws an exception. For asynchronous HTTP/SSE services, the application schedules the execution thread and forwards events through `RuntimeListener`.

- History for each new request comes from `AgentRequest.messages`; the framework does not query history automatically by conversation ID.
- `systemPrompt` is provided by the application; the framework separately assembles workspace, task, MCP, and Skills information.
- A request may set `modelConfig` to override the entire model configuration, or set `modelProvider` to switch the provider used by the application configuration. When both are set, `modelConfig` wins; no per-field merge is performed.
- `agent.createExecution(request)` creates a `CREATED` execution and saves it through the repository; `agent.execute(execution)` runs an existing object. Resuming preserves the original execution identity.

## Workspace

When `workspaceSpec` is not set, or is set to `null`, the local workspace is used by default, rooted at `System.getProperty("user.dir")`.

```java
import com.summit.core.workspace.BasicWorkspaceSpec;

AgentRequest request = AgentRequest.builder()
        .messages(List.of(UserMessageEntity.from("Inspect the working directory")))
        .workspaceSpec(new BasicWorkspaceSpec("local", "D:/projects/example"))
        .build();
```

The local directory must already exist. The same provider, scope, and provider identity configuration reuses a managed workspace; finishing a request does not destroy it automatically. Call `WorkspaceManager.destroy(ref)` when cleanup is needed. The local provider uses `ATTACHED` ownership and is not responsible for deleting user directories.

Docker is an optional module: add the `harness-sandbox-docker` dependency and pass a `DockerWorkspaceSpec` explicitly. See the [Docker README](harness-sandbox-docker/src/main/docker/README.md) for image builds. To customize a remote environment, implement `WorkspaceProvider` and return your own `Workspace` and `WorkspaceBridge`.

`allowOutsideWorkspace` defaults to `false` and is passed to tools with the request. Custom tools should use the workspace path resolution, `encloses` checks, and the bridge; when a tool uses host file APIs or starts processes directly, the framework does not automatically convert those operations into workspace operations.

## Tool Registration and Permissions

Implement `ToolExecutor.execute(ToolExecution)` and register the `ToolDefinition` as a Spring bean; the starter collects these definitions to build the static `ToolRegistry`. Registering only an executor bean does not generate a tool definition.

```java
import com.summit.core.tool.ConcurrentPolicy;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecutor;
import org.springframework.context.annotation.Bean;

@Bean
ToolDefinition<ToolExecutor> projectInfoTool() {
    return ToolDefinition.<ToolExecutor>builder()
            .id("project_info")
            .name("project_info")
            .description("Returns the working directory of the current project")
            .parametersJsonSchema("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}")
            .executor(call -> ToolExecuteResult.success(call.getWorkspace().workDir()))
            .timeout(10L)
            .maxOutput(1000)
            .concurrentPolicy(ConcurrentPolicy.READ_ONLY)
            .build();
}
```

Put the bean method in an application `@Configuration` class, then authorize the tool in the request:

```java
AgentRequest request = AgentRequest.builder()
        .messages(List.of(UserMessageEntity.from("Tell me the current project directory")))
        .toolList(List.of("project_info"))
        .build();
```

- `toolList` is the static tool allowlist; both `null` and an empty list expose no static tools, including framework built-ins.
- A tool must already be registered and present in the allowlist. The execution layer still checks permissions; calls that are not found or not authorized return an error result.
- Tool names must be unique within the static registry. An executor returns `success(...)`, `err(...)`, or `promise(...)`; the framework fills in the tool call ID and tool definition.
- `timeout` is in seconds; `<= 0` means no framework execution timeout. `maxOutput` is enforced by the default `ToolInterceptor` through `Tokenizer.truncate`; context compaction results do not go through this truncation.
- `ToolExecution` carries raw JSON arguments, execution/tool call/response identities, the workspace, request attributes, event metadata, Skills configuration, and MCP scope. Argument parsing, business validation, and side effects belong to the executor implementation.
- An executor defaults to `requiresWorkspace() == true`. Tools that only access host resources may explicitly return `false`, for example the Skills resource reader.

## Tool Concurrency Policy and Execution Behavior

`ToolDefinition.concurrentPolicy` declares a tool's concurrency capability:

| Policy | Current scheduling behavior | Applicable cases |
| --- | --- | --- |
| `READ_ONLY` | May join a concurrent batch | Side-effect-free reads and queries |
| `ISOLATED_MUTATION` | May join a concurrent batch | Writes where the tool isolates itself, e.g. each task writes to its own directory |
| `SERIAL_MUTATION` | Forces the whole batch to run serially | File modifications or shared-resource writes that affect each other |
| `null` | Also forces the whole batch to run serially | Tools that do not declare concurrency capability |

The policy is a scheduling declaration. The framework does not verify that a tool is really read-only, nor does it create an isolated environment automatically for `ISOLATED_MUTATION`.

### How a Batch Is Executed

All tool calls in a single model response form one batch. The whole batch enters the concurrent branch only when there is more than one call and every tool exists, allows concurrency, and has `timeout > 0`. If any tool fails those conditions, the entire batch runs serially in the order provided by the model; read and write tools are not regrouped.

The Spring default concurrency limit is 5:

```yaml
lingxi:
  tool:
    concurrent-tool-limit: 5
```

The current concurrent branch runs the first `limit` calls simultaneously, waits for all of them to finish, and then runs the remaining calls serially. **It is not a continuously refilling thread-pool queue.** For example, with 7 concurrent-capable tools and a limit of 3:

```text
Concurrent start: call-1, call-2, call-3
Wait for all 3 calls to finish
Serial execution: call-4 -> call-5 -> call-6 -> call-7
Aggregate results, continue the loop
```

The limit is clamped at execution time to `[1, batch size]`; when the manager is constructed directly and the limit is `null`, the batch size is used. Tools run on a separate virtual-thread executor and do not occupy the JVM common pool. Serial calls with a finite timeout are also submitted to virtual threads, but the caller waits for them one by one.

### Ordering, Errors, and Concurrency Boundaries

- Results are returned in the order requested by the model so the context can correlate the original tool call; concurrent start/end events may arrive in a different order.
- Regular tool exceptions, timeouts, or rejections are converted into an error result for that tool and by default do not skip other calls in the batch. The framework does not retry tools automatically.
- On timeout it executes `future.cancel(true)` and returns `TIMED_OUT`. Executors should respond to interruption, close network resources, and clean up processes they started; interruption does not mean external side effects have stopped or been rolled back.
- `SERIAL_MUTATION` and the concurrency limit only constrain **the batch of this one manager call**. Different executions, even when sharing a workspace, do not thereby obtain a global write lock. Cross-execution mutual exclusion, idempotency, and rate limiting should be implemented by the application or a custom manager.
- Executors, tool policies, tool interceptors, and event listeners are usually shared beans and must handle concurrent calls; do not rely on request-thread `ThreadLocal` values propagating automatically to virtual threads.
- `ToolExecutionPolicy` runs before the executor and its timeout starts, invoked in ascending `order()`; returning `null` allows the call through, and the first non-null result short-circuits. Policies may also run concurrently inside a concurrent batch and should not block waiting for human input.
- When a policy short-circuits, the executor's `ToolInterceptor` chain is not entered and no `ToolCallStartEvent` is emitted, but a `ToolCallEndEvent` is. During executor execution, `pre` runs ascending and `after` runs in reverse; execution exceptions go to `onError`.

Source: [DefaultToolExecutionManager](harness-runtime/src/main/java/com/summit/runtime/tool/DefaultToolExecutionManager.java), [ConcurrentPolicy](harness-core/src/main/java/com/summit/core/tool/ConcurrentPolicy.java).

## Human Interaction and Approval Resume

The framework hands execution back to the application using the `PROMISE` tool result. It is a marker meaning "suspend after this round's results are submitted" — not a Java Future, and the framework does not hold a thread waiting for the user.

### Integration Steps

1. Define the tools or `ToolExecutionPolicy` that require approval. A policy saves the pending approval record and returns `PROMISE` before the tool actually executes, avoiding premature side effects; a dedicated questioning tool may also return `PROMISE` directly.
2. The application persists the interaction record: execution ID, original tool call ID, response ID, tool name, arguments, workspace configuration/reference, user identity, pending action, and decision status. `toolMetaData` can be used to render prompts, but the framework does not treat it as a persisted approval record.
3. Listen for the `PROMISED` status on `ToolCallEndEvent` to present the interaction; allow resume only after the execution reaches the `SUSPENDED` checkpoint. Use `RuntimeListener.onExecutionSuspended` to notify the frontend that the checkpoint is ready.
4. After the user answers or approves, the application reads the latest execution snapshot and interaction record and verifies permissions, status, and whether the decision was already processed. Use an application transaction, version check, or atomic claim to prevent processing the same approval twice.
5. Plain questioning: append the user's answer via `ConversationManager.appendUserMessage(execution, answer)`, save, then call `ExecutionControl.resume(execution)`. The original Promise tool result may be kept as a record of the question.
6. Side-effect approval: first call `beginApproval(execution)` to persist the processing state; execute the action from the original record or produce a rejection result, then update the `ToolMessageEntity` corresponding to the original tool call with the actual result and call `finishApproval` to save it. Also complete the application transaction for the interaction record before resuming.
7. `finishApproval(execution, CONTINUE)` saves the execution back to `SUSPENDED`; then `resume(execution)` continues model invocation. If the approved action fails, call `failApproval`; if the application confirms the execution should be cancelled, use `ApprovalOutcome.CANCELLED` to end it and do not resume.

The following policy example shows how to request approval. `terminal` is a sample business tool name; replace it with the tool name actually registered. A real application should also persist the application record from step 2 before returning:

```java
import com.summit.core.tool.ToolExecutionPolicy;
import java.util.Map;

@Bean
ToolExecutionPolicy terminalApprovalPolicy() {
    return call -> {
        if (!"terminal".equals(call.getToolDefinition().name())) {
            return null;
        }
        return ToolExecuteResult.promise("Waiting for user approval of the terminal command", Map.of(
                "interactionType", "command_approval",
                "toolCallId", call.getId(),
                "arguments", call.getArgs()));
    };
}
```

Side-effect approval flow (application pseudocode):

```text
Claim the pending approval record and read the latest SUSPENDED execution
beginApproval(execution)                 // SUSPENDED -> RUNNING, save before performing side effects
Execute the approved action with the original identity, arguments, and workspace, or produce a rejection result
Update the tool message for the original toolCallId in the execution; commit the application interaction result
finishApproval(execution, CONTINUE)      // RUNNING -> SUSPENDED, saves the actual result
Schedule resume(execution) after the application commits    // runs synchronously, may be called by an application worker
```

Do not route an approved action back through a policy that always requires approval, otherwise the action returns a Promise again. The application needs an explicit post-approval execution path while preserving the original arguments, permission checks, and workspace validation; do not rely on the model to regenerate the approved action.

### What the Framework Does on Suspend and Resume

- The tool manager executes the entire batch. After one call returns a Promise, **other tools in the same batch still run**, including the serial tail; it is not a signal to stop the remaining tools immediately.
- The loop adds this round's assistant message and all tool results to the context, reports this round's usage, and saves a checkpoint; if a cancellation signal exists it takes priority, otherwise it returns `SUSPENDED`.
- The runtime saves the suspended state, publishes the suspend event, and unregisters this active execution signal. `execute(...)` returns the suspended object and does not schedule a resume automatically.
- `resume` accepts only a `SUSPENDED` object and calls the agent synchronously. Resuming issues a new model request from the saved context; it does not continue from the interrupted Java instruction position, and it does not automatically fulfill the original tool Promise.
- `beginApproval` / `finishApproval` provide state transitions and repository persistence; the default implementation does not handle database claiming, approval permissions, action execution, result message replacement, duplicate request deduplication, or a resume queue.
- `ExecutionControl.suspend(id)` / `cancel(id)` are cooperative control requests for a **running** execution; when they take effect is determined by the runtime boundary and the streaming model handler. Subsequent decisions for an already-suspended object belong to the application.
- The default in-memory repository only prevents the same ID from entering an active loop concurrently; it does not replace approval transactions, distributed locks, or snapshot versioning.

To continue interactions across processes or restarts, implement a persistent `ExecutionRepository` and provide an application interaction table and a resume worker. The default repository is an in-process snapshot that removes snapshots on terminal states; it is not an audit history database.

Source: [ToolExecuteResult](harness-core/src/main/java/com/summit/core/tool/ToolExecuteResult.java), [AgentLoopStepRunner](harness-runtime/src/main/java/com/summit/runtime/loop/AgentLoopStepRunner.java), [ExecutionControl](harness-core/src/main/java/com/summit/core/runtime/loop/ExecutionControl.java).

## Intercepting the Loop Lifecycle

Register one or more `LoopInterceptor` beans; the framework invokes them synchronously in ascending `order()`. It suits injecting external messages, enforcing business decisions, and collecting per-round metrics; pure event presentation usually uses `RuntimeListener`.

```java
import com.summit.core.runtime.loop.InterceptorResult;
import com.summit.core.runtime.loop.LoopContext;
import com.summit.core.runtime.loop.LoopInterceptor;
import com.summit.core.runtime.loop.LoopResult;

@Bean
LoopInterceptor businessLoopInterceptor() {
    return new LoopInterceptor() {
        public int order() { return 100; }

        public InterceptorResult onBeforeModelInvoke(LoopContext context) {
            var attributes = context.getLoopMessages().getExecution().getAgentRequest()
                    .runtimeParametersOrDefault().getAttributes();
            if (Boolean.TRUE.equals(attributes.get("pauseBeforeModel"))) {
                return InterceptorResult.of(LoopResult.suspended("Waiting for application confirmation"));
            }
            // If external messages have been claimed, call context.appendMessage(...) here.
            return InterceptorResult.NONE;
        }
    };
}
```

The `pauseBeforeModel` attribute in the example is application-defined and must be updated or removed before resuming, otherwise the next round suspends again. The framework does not interpret this key.

| Callback | Trigger point | Notes |
| --- | --- | --- |
| `onLoopStart` | Entrance of each round | Called every round, not once per execution |
| `onBeforeModelInvoke` | Before request construction, budget check, and compaction | Append external input here; it participates in this round's budget calculation |
| `onAfterModelInvoke` | After the model returns, before the assistant message is committed | Can inspect the response; when short-circuiting, this round's response is not yet written to the context |
| `onBeforeToolCall` | When tool calls exist, before the whole batch executes | Can stop the entire batch of tool calls |
| `onAfterToolCall` | After the whole batch returns, before Promise/compaction handling and checkpoint commit | Side effects may have occurred, but this round's results are not yet committed |
| `onBeforeComplete` | After a response without tool calls is committed and checkpointed, before natural completion | Returning suspension keeps the reply, transcript and token accounting; `NONE`/`CONTINUE` allow completion |
| `onLoopEnd` | When a round that has been entered exits | Triggered on failure, suspension, and normal exit; the return value does not change the loop result |
| `onRunEnd` | When the runtime reaches `COMPLETED` / `CANCELLED` / `FAILED` | Not triggered on suspension; notification exceptions are only logged, and the return value does not change the terminal state |

Control callbacks return `InterceptorResult.NONE` to continue; returning `InterceptorResult.of(LoopResult.suspended(...))`, `cancelled(...)`, or `completed()` stops this loop, and later interceptors in the same phase are not invoked. If `onAfterToolCall` wants to keep this round's results and suspend, prefer returning `NONE` so the tool's `PROMISE` follows the normal commit path; short-circuiting directly bypasses subsequent message commits.

Use `onBeforeComplete` to postpone completion while waiting for application work such as child agents. Inspect the committed response through `context.getLoopMessages().getResponse()` and return `InterceptorResult.of(LoopResult.suspended("Waiting for child agents"))` when needed; do not commit it again. The execution is still `RUNNING` at this point. The runtime checks cancellation/suspension signals before and after this hook, with cancellation taking priority. The application owns delivery and scheduling a resume after the execution becomes `SUSPENDED`.

Round response/tool data remains available through `onLoopEnd` and is then cleared on every exit, including compaction continuation, suspension and failure.

`catchErr()` defaults to `true`: the dispatcher logs the exception and continues the chain. Override it to `false` when failures should propagate; exceptions in `onLoopEnd` may fail the execution, and are kept as suppressed exceptions when a primary exception already exists. The runtime's `onRunEnd` notification is always best-effort. The runtime budget is checked independently by `RuntimeBoundaryChecker` and cannot be bypassed by "interceptor continues".

Use `LoopContext.appendMessage(...)` only during the callback on the executing thread; do not save it for asynchronous threads to mutate the context. When receiving messages in parallel, the application should enqueue them first, then claim and append them from the before-model callback.

Source: [LoopInterceptor](harness-core/src/main/java/com/summit/core/runtime/loop/LoopInterceptor.java), [DefaultLoopInterceptorProcessor](harness-runtime/src/main/java/com/summit/runtime/loop/DefaultLoopInterceptorProcessor.java).

## Runtime Events and Persistence

Register `RuntimeListener` beans and override `onPartialText`, `onPartialThinking`, `onAiMessage`, `onToolCall`, `onToolCallOutput`, `onContextUpdate`, and the execution start/resume/suspend/complete/cancel/error callbacks as needed. The framework dispatches synchronously through `RuntimeEventPublisher`; HTTP/SSE, WebSocket, and UI protocols are implemented by the application. Slow listeners block the corresponding calling thread.

`AgentRuntimeParameters.attributes` is for application policy input; `eventMetaData` is correlation information carried into events and the transcript once selected. Correlate model and tool calls using execution ID, response ID, and tool call ID; do not treat event arrival order as context commit order.

- `ExecutionRepository` persists resumable checkpoints and manages cancel/suspend signals for active executions. A persistent implementation should save independent snapshots and implement `afterCommit` correctly so notifications run after the application transaction commits.
- `ConversationTranscriptSink` receives model/tool rounds already added to the conversation, suitable for persisting complete display history; copy or serialize mutable messages before returning to prevent later compaction from mutating history objects.
- Transcript and model context serve different purposes: compaction changes `Execution.messages`, while the application may keep a full transcript. Storing external user input remains the application's responsibility.
- `ExecutionFailureObserver` is for compensation observation after a runtime execution failure and does not cover all initialization failures; failures in the workspace/model/MCP preparation phase, before the runtime is entered, must be handled by the application at the call layer.

## MCP and Skills

### MCP

Configure servers in `AgentRequest.mcpConfig`. The default `AgentScopeMcpProvider` opens connections per execution and discovers remote tools; Streamable HTTP and stdio are supported, and the built-in client does not support the legacy SSE transport.

MCP tools live in the request `McpToolScope` and are not registered in the global static `ToolRegistry`. Remote tools are named `mcp_` plus the original name by default; a connection or discovery failure for a single server is logged and that server is skipped. When multiple servers expose tools with the same name, the scope keeps the first one.

Discovery and model visibility are separate: the prompt provides a server summary, and only tools that `search_tool` finds and discloses enter the tool list of later model requests. Therefore, when using progressive discovery you must enable `search_tool` and authorize it in the static `toolList`. MCP tools already discovered for this request are allowed at the execution layer by scope membership and do not need to be written into the static allowlist in advance.

Suspension preserves the MCP scope of that execution, and resuming the same execution reuses the scope; the terminal execution path closes request-owned connections in the scope. The cache lives in the current agent process, so connections are re-established when resuming across restarts. Long suspensions require the application to schedule cancellation or continuation; the framework sets no automatic expiry. Shared connections need a custom `ScopeMcpProvider` / `McpSession`, whose lifecycle is managed by the provider.

### Skills

`SkillConfig` points at a host Skills directory; the default loader scans `SKILL.md` metadata and adds it to the prompt, while the actual content is read on demand by `read_skill`.

```java
import com.summit.core.conf.SkillConfig;
import java.nio.file.Path;

AgentRequest request = AgentRequest.builder()
        .messages(List.of(UserMessageEntity.from("Review the code using the project Skill")))
        .skillConfig(new SkillConfig(Path.of("D:/skills")))
        .toolList(List.of("read_skill"))
        .build();
```

The default `SkillResolver` depends on CommonMark; when that parsing dependency is missing, ordinary requests can still start, while Skills requests need a custom resolver/loader or the dependency added. `read_skill` is enabled by default in the kernel tools module and can be disabled via `lingxi.agent.runtime.tool.read-skill.enabled`. The default reader accesses the Skills host directory, does not borrow the Docker workspace, and does not execute Skill scripts automatically.

## Context Compaction

When no dedicated compact model is configured, compaction reuses the full chat model configuration. To use a separate model, configure `lingxi.agent.model.conf.compact`:

```yaml
lingxi:
  agent:
    model:
      conf:
        compact:
          base-url: https://api.example.com/v1
          api-key: ${COMPACT_MODEL_API_KEY}
          model-name: your-compact-model
          timeout: 60s
```

The runtime boundary decides when to compact based on budget and compaction thresholds. `ContextCompacter` decides how to compact; `ContextAttachmentProvider` supplies business state that must be preserved; `Tokenizer` / `TokenEstimator` provide budget calculation and truncation.

When the model proactively calls `compact_context`, that tool must be registered and authorized in the request allowlist. Only a dedicated round with a single successful compaction tool result replaces the context; mixed tool batches keep all ordinary results. The number of consecutive compaction rounds is limited by `max-consecutive-compactions` to prevent a compaction loop that cannot converge.

## SPI and Extension Contract Reference

The following lists all public interfaces based on the current source, distinguishing application extension points, low-level replacement contracts, and auxiliary data/callback interfaces. The list does not imply that every interface can be replaced automatically by "dropping in a bean". Spring injection rules are defined by the [autoconfigure source](harness-spring-boot-autoconfigure/src/main/java/com/summit/harness/springbootautoconfigure/config).

### Common Business SPIs

All packages are prefixed with `com.summit.core`:

| Interface (package suffix) | Responsibility | How to plug in |
| --- | --- | --- |
| `tool.ToolExecutor` | Executes a single tool, declares workspace requirements | Put into a `ToolDefinition` bean |
| `tool.ToolExecutionPolicy` | Tool admission, rejection, approval suspension | Multiple beans collected, ascending `order()` |
| `tool.ToolInterceptor` | Pre/post processing and error observation around the executor | Multiple beans collected; keep the default output truncator |
| `runtime.loop.LoopInterceptor` | Per-round business decisions and message injection | Multiple beans collected, ascending `order()` |
| `runtime.RuntimeListener` | Output and lifecycle event consumption | Multiple beans collected |
| `runtime.loop.ExecutionFailureObserver` | Compensation observation after runtime failure | Multiple beans collected; a single failure is only logged |
| `runtime.loop.ExecutionRepository` | Snapshots, active registration, control signals, post-commit notification | Same-type bean replaces the in-memory implementation |
| `conversation.api.ConversationTranscriptSink` | Display history of committed rounds | Optional bean; has response ID / metadata overloads |
| `compact.ContextAttachmentProvider` | Business state text during compaction | Replace the bean named `contextAttachmentProvider` |
| `skill.SkillLoader` | Loads Skills metadata | Same-type bean replacement |
| `skill.SkillResolver` | Parses a single `SKILL.md` | Same-type bean replacement |
| `mcp.ScopeMcpProvider` | Opens the request MCP scope | Same-type bean replacement |
| `mcp.McpSession` | Tool discovery, connection ownership, and close | Provided by the provider |
| `mcp.McpToolExecutor` | Marker contract for MCP tool execution | The MCP tool definition provides the executor |
| `workspace.WorkspaceProvider` | identity, provision/open/reconcile/inspect/destroy/discover | Multiple beans registered, type must be unique |

### Model, Context, and Execution Kernel SPIs

All packages are prefixed with `com.summit.core`:

| Interface (package suffix) | Responsibility and replacement boundary |
| --- | --- |
| `agent.Agent` | Top-level create/execute contract; the default implementation extends runtime's `ChatAgent` |
| `runtime.RuntimeFactory` | Creates a runtime from model, workspace, and MCP scope; replaceable by a same-type bean |
| `runtime.ExecutionRuntime` | Executes an execution; provided by the factory |
| `runtime.loop.ExecutionControl` | cancel/suspend/resume, approval state transitions, failure commit; replaceable by a same-type bean |
| `runtime.loop.ActiveExecutionRegistry` | Active execution signal registration, unregistration, and control; parent contract of the repository, not a standalone default storage bean |
| `runtime.loop.RuntimeBoundaryChecker` | Budget boundary decisions before the model and after ordinary tool rounds; replaceable by a same-type bean |
| `runtime.loop.LoopInterceptorProcessor` | Loop callback dispatch; replaceable by a same-type bean |
| `runtime.loop.lifestyle.RuntimeLifeStyleManager` | Runtime lifecycle notification; replaceable by a same-type bean |
| `tool.ToolExecutionManager` | Whole-batch tool scheduling, admission, and result events; replaceable by a same-type bean |
| `interceptor.RuntimeInterceptor<T>` | Generic pre/after/onError/order contract; the default entry point is `ToolInterceptor` |
| `interceptor.InterceptorProcessor<R>` | Method invocation and interceptor chain dispatch; the default injection is `InterceptorProcessor<ToolExecution>` |
| `model.ModelProvider<T>` | Creates a model by name; collected into registries by chat/streaming generics |
| `model.chat.ChatModelProvider` | Synchronous chat provider; register a custom name, or replace by default bean name |
| `model.streaming.StreamingChatModelProvider` | Streaming chat provider; register a custom name, or replace by default bean name |
| `model.compact.CompactContextModelProvider` | Compaction chat provider, extends `ChatModelProvider` |
| `model.chat.ChatModel` | Synchronous model invocation; created by the provider |
| `model.streaming.StreamingChatModel` | Streaming model invocation; created by the provider |
| `model.ModelInvoker` | Invokes a specific model; selected by the request model factory |
| `model.RequestModelInvokerFactory` | Request model resolution and invoker selection; replaceable by a same-type bean |
| `conversation.ConversationManager` | Initialization, round appending, message injection, summary rebuild; replaceable by a same-type bean |
| `prompt.PromptAssembler` | Assembles prompts in segments; optional bean or custom conversation manager; beware concurrency of mutable builder state |
| `compact.ContextCompacter` | Blocking compaction; current auto-configuration injects the concrete `DefaultManualCompacter` / `DefaultModelCompacter`, so registering only this interface bean does not replace both |
| `compact.Tokenizer` | Token counting and truncation; replaceable by a same-type bean |
| `adapter.TokenEstimator` | Low-level token estimation; replaceable by a same-type bean |
| `workspace.WorkspaceManager` | Provider registration and workspace lifecycle coordination; replaceable by a same-type bean |
| `workspace.WorkspaceStore` | Workspace record storage; replaceable by a same-type bean for the in-memory implementation |
| `runtime.workspace.Workspace` | Environment, path boundaries, and bridge; returned by the provider |
| `runtime.workspace.WorkspaceBridge` | File and command environment adaptation layer; returned by the workspace |

### Streaming Callback and Auxiliary Data Contracts

These interfaces are parts of models/messages/events, usually passed in by a provider or the runtime, and are not auto-discovered business policy beans:

| Interface (under `com.summit.core`) | Purpose |
| --- | --- |
| `model.streaming.StreamingChatResponseHandler` | Text, thinking, tool argument fragments, final response, and error callbacks |
| `model.streaming.StreamingModelResponseHandler` | Sub-contract dedicated to the runtime streaming handler |
| `model.streaming.StreamingHandler` | Streaming transport cancel / cancelled state |
| `workspace.WorkspaceSpec` | provider, directory, scope, configuration data; extended types must configure a JSON subtype |
| `conversation.message.Message` | Message data contract |
| `conversation.message.content.Content` | Text/image/audio/video/PDF content contract |
| `conversation.event.TypedEvent` | Typed event |
| `conversation.event.AgentEvent` | Common agent event identity and metadata |
| `conversation.event.ToolCallEvent` | Tool event identity |
| `conf.McpConfig.Conf` | transport configuration sealed interface; allowed types are defined by the framework |

### Extension Contracts Outside core

| Interface / extension class | Purpose and plug-in boundary |
| --- | --- |
| `com.summit.adapter.langchain4j.codec.MessageCodec<M,R>` | Converts between harness and LangChain4j messages/responses; for custom adapters, registering a standalone bean does not replace existing adapters |
| `com.summit.adapter.langchain4j.codec.ToolCodec<T>` | Tool schema conversion; for custom adapters |
| `com.summit.runtime.workspace.WorkspaceDestroyer` | Destroy/cleanup retry; default reaper implementation, constructed/configured by the consumer |
| `com.summit.runtime.agent.ChatAgent` (abstract class) | Custom identity and workspace/model/MCP resolution hooks; when a bean of this type exists, the default agent stands down |
| `com.summit.adapter.langchain4j.mcp.McpClientFactory` (class) | transport/client creation; replaceable by a same-type bean |

There are also configurable concrete components — `ToolRegistry`, `ModelProviderRegistry`, `RuntimeEventPublisher`, `ContextUsageReporter`, `AgentConfig` / `ProgressiveSqueezePolicy`, and `ObjectMapper` — which are not SPI interfaces. When overriding default beans, pay attention to the difference between type conditions and name conditions: for example, `toolInterceptor`, `manualCompacter`, `modelCompacter`, `chatModelConfig`, and the model provider/registry have name-based wiring. Replacing a compactor must satisfy the current concrete type injection, or replace the runtime factory / boundary checker together.

Source: [Core contracts](harness-core/src/main/java/com/summit/core), [Runtime implementation](harness-runtime/src/main/java/com/summit/runtime), [Spring auto-configuration](harness-spring-boot-autoconfigure/src/main/java/com/summit/harness/springbootautoconfigure/config), [LangChain4j Adapter](harness-adapter-langchain4j/src/main/java/com/summit/adapter/langchain4j).
