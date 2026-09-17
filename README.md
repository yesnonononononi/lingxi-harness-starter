```xml
    <dependency>
        <groupId>io.github.yesnonononononi</groupId>
        <artifactId>lingXi-harness-agent</artifactId>
        <version>1.0.3</version>
    </dependency>
```

## Architecture boundary

The starter is an application-neutral agent runtime. It owns model invocation, the loop, tool
dispatch, workspaces, conversations and lifecycle events. Two rules define the boundary:

- **The framework ships mechanisms, not product workflows.** Plan/Task models, plan tools, user
  choices and command approval belong to the consuming application; the starter neither defines
  nor auto-configures them.
- **Human approval happens outside the tool execution timeout.** A call is admitted (or refused)
  through `ToolExecutionPolicy`, which runs *before* the tool's own timeout starts, and the wait
  itself is a `LoopSuspender` suspension with its own timeout. Waiting inside a `ToolInterceptor`
  or a `ToolExecutor` would spend the tool's execution budget on the human.

Applications extend the loop through these SPIs:

- `AgentLoopHook` observes tool/plain-text turns and may continue, stop, cancel or change the
  execution boundary without introducing a product domain into the runtime.
- `LoopSuspender` pauses the current loop for any external decision. Its payload and topic are
  application-defined, so the same bean serves plan, command and user-choice approval. The
  included `InMemoryLoopSuspender` is a single-node development default.
- `ToolExecutionPolicy` decides whether a tool call is admitted at all, before its timeout starts.

Application state that must survive context compaction is supplied through
`ContextAttachmentProvider`; the runtime has no special dependency on a plan store.

The in-memory suspender's limitations and the planned SPI refinements — a generic suspension
notification, pending-suspension listing, timeout events, durable implementations — are tracked in
[`docs/adr/loop-suspension-boundary.md`](docs/adr/loop-suspension-boundary.md). They are **not
implemented yet**; nothing below that line is an available feature.

Docker is also optional. Add `harness-sandbox-docker` explicitly when the application needs a
Docker `WorkspaceProvider`; the base starter only supplies the local provider.

## Workspace lifecycle prototype

`WorkspaceSpec` describes a desired environment, `WorkspaceRecord` is its persistable state,
and `WorkspaceManager` resolves live runtimes through registered `WorkspaceProvider` backends.
The starter supplies a local provider and an in-memory store by default. Docker support is
isolated in the optional `harness-sandbox-docker` module.

```java
WorkspaceRecord record = workspaceManager.create(new BasicWorkspaceSpec("local", projectDirectory));

AgentRequest request = AgentRequest.builder()
        .input("inspect this project")
        .workspace(workspaceManager.acquire(record.ref()))   // caller owns the lifecycle
        .build();
```

Passing a `WorkspaceSpec` instead lets the framework create, acquire and destroy the workspace for
that execution. Managed conversations persist the `WorkspaceRef`, not the live bridge-bearing
`Workspace`; `ConversationManager.workspace(sessionId)` resolves it lazily through the manager.

## Docker sandbox

The optional `harness-sandbox-docker` module runs an agent inside an isolated container. A sandbox is
only useful when the runtimes a project needs are already inside it, so the module ships a
general-purpose development image — JDK 21, Maven, Git, Node.js, npm and bash — instead of a bare
base image:

```powershell
powershell -ExecutionPolicy Bypass -File .\harness-sandbox-docker\src\main\docker\build.ps1
```

`DockerSandboxImage.DEFAULT` names that image, and every sandbox is created from it unless a
deployment configures its own. Containers are reused by name; a framework-managed container that was
built from a different image is removed and recreated automatically, so changing the image always
results in a sandbox that really carries the configured toolchain. Containers the framework does not
manage are never touched.

## Minimal request

`input` plus a workspace are the only required entries; everything else has a default.

```java
defaultChatAgent.execute(AgentRequest.builder()
        .input("inspect this project")
        .workspace(liveWorkspace)   // or .workspaceSpec(spec) to let the framework create and destroy one
        .build());
```

Defaults: `sessionId` = `default`; `sessionName` / `systemPrompt` / `task` = `null`; `toolList` = `null`
(all registered tools); `runtimeParameters` = `EXECUTE` loop with the executor's default confirm level;
model = `application.yml`. A workspace is mandatory — either a live `Workspace` or a `WorkspaceSpec`
whose `provider` names an installed provider (`local` by default) and whose `workDir` exists.

## Model configuration

The default model comes from `application.yml` (`agent.chat.*`, `agent.compact-context.*`).
A request overrides it in one of two flat ways — there is no field-level fallback and no precedence chain:

| `AgentRequest` | Effective model |
| --- | --- |
| `modelConfig(cfg)` | `cfg` used as is; `modelProvider` is ignored |
| `modelProvider(name)` | application config with `provider = name` |
| neither | application config |

```java
AgentRequest.builder()
        .input("inspect this project")
        .workspace(workspace)
        .modelConfig(ModelConfig.builder()
                .provider("default")
                .baseUrl("https://api.example/v1")
                .apiKey("...")
                .modelName("gpt-4o")
                .build())   // partial config is NOT merged: unset fields stay null
        .build();
```

`provider` must name a registered `ModelProvider` (`default` for OpenAI-protocol chat,
`default-streaming` for streaming, or your own bean); leaving it blank uses the application
default provider. Resolved configurations are cached, so identical requests reuse one model instance.
