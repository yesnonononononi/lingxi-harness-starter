```xml
    <dependency>
        <groupId>io.github.yesnonononononi</groupId>
        <artifactId>lingxi-harness-spring-boot-starter</artifactId>
        <version>1.0.2</version>
    </dependency>
```

## Minimal Spring Boot setup

For an OpenAI-compatible model, this is the complete minimum `application.yml`:

```yaml
lingxi:
  agent:
    model:
      conf:
        chat:
          base-url: https://api.example.com/v1
          api-key: ${MODEL_API_KEY}
          model-name: your-model
```

If `lingxi.agent.model.conf.compact` is not configured, context compaction automatically reuses the
complete chat model configuration. A separate compact block is needed only when compaction should
use another provider or model. Tools are opt-in and are not required for a basic conversational
agent. See the [Starter README](lingxi-harness-spring-boot-starter/README.md) for the minimal Java
request and optional tool configuration.

## Architecture boundary

The starter is an application-neutral agent runtime. It owns model invocation, the loop, tool
dispatch, workspaces, conversations and lifecycle events. Two rules define the boundary:

- **The framework ships mechanisms, not product workflows.** Plan/Task models, plan tools, user
  choices and command approval belong to the consuming application; the starter neither defines
  nor auto-configures them.
- **Suspension is a loop-control result, not a blocking workflow.** A tool or
  `ToolExecutionPolicy` returns `ToolExecuteResult.promise(...)`; the runtime commits the complete
  assistant/tool batch and ends the current run in `SUSPENDED`. It does not wait on a thread or
  interpret why the application requested the suspension.

Applications extend the loop through these SPIs:

- `AgentLoopHook` observes the final execution outcome. Application-specific decisions after a
  model response, such as continuing with another directive or changing the tool set, are not
  built into the runtime.
- `ToolExecutionPolicy` decides whether a tool call is admitted at all, before its timeout starts.
- `ToolResultType.PROMISE` lets either a tool or a policy commit a result and request that the
  current loop stop in the generic suspended state.

Application state that must survive context compaction is supplied through
`ContextAttachmentProvider`; the runtime has no special dependency on a plan store.

The suspension boundary and the responsibilities left to applications are documented in
[`docs/adr/loop-suspension-boundary.md`](docs/adr/loop-suspension-boundary.md).

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
        .messages(List.of(UserMessageEntity.from("inspect this project")))
        .workspace(workspaceManager.acquire(record.ref()))   // caller owns the lifecycle
        .build();
```

Passing a `WorkspaceSpec` instead hands the whole lifecycle to the framework. The identity of the
resource is **derived from the spec**, never supplied by the caller: a provider natural key (the
shared host directory for a sandbox, the working directory for a local workspace) is hashed into a
`WorkspaceRef`, so the same desired environment always resolves to the same workspace — across
requests and across restarts.

```java
Workspace workspace = workspaceManager.acquire(
        new DockerWorkspaceSpec("/workspace", null, image, hostDir, null, true));
```

A workspace resolved this way is owned by the framework and outlives the request that created it:
creating a sandbox costs seconds and carries the toolchain state the run depends on, so tearing it
down after every request would throw that state away and re-pay the cost on the next turn.
Destruction is explicit (`workspaceManager.destroy(ref)`) or driven by idle reclamation, and is
governed by `ResourceOwnership`, not by how the workspace was requested. The caller persists any
workspace specification needed for a later execution; the harness keeps no conversation or
workspace association behind an implicit session identifier.

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

The complete message context plus a workspace are the required entries. The harness copies the
request list, runs the agentic loop, and returns the authoritative new context on `Execution`.

```java
Execution execution = defaultChatAgent.execute(AgentRequest.builder()
        .messages(List.of(UserMessageEntity.from("inspect this project")))
        .workspace(liveWorkspace)   // or .workspaceSpec(spec) to let the framework create and destroy one
        .build());

conversationRepository.save(conversationId, execution.getMessages());
AiMessageEntity answer = execution.getAiMessage();
```

There is no `sessionId` and no framework conversation store. `systemPrompt` / `task` default to
`null`; `toolList` defaults to `null`
(all registered tools); `runtimeParameters` = `EXECUTE` loop with the executor's default confirm level;
model = `application.yml`. A workspace is mandatory — either a live `Workspace` or a `WorkspaceSpec`
whose `provider` names an installed provider (`local` by default) and whose `workDir` exists.

## Model configuration

The default model comes from `application.yml` (`lingxi.agent.model.conf.chat.*`). An explicitly
configured compact model uses `lingxi.agent.model.conf.compact.*`; otherwise it reuses chat.
A request overrides it in one of two flat ways — there is no field-level fallback and no precedence chain:

| `AgentRequest` | Effective model |
| --- | --- |
| `modelConfig(cfg)` with a `provider` | `cfg` used as is; `modelProvider` is ignored |
| `modelConfig(cfg)` without a `provider` | `baseUrl`, `apiKey` and `modelName` are required; provider is `default-streaming` when `returnThinking` is on, otherwise `default` |
| `modelProvider(name)` | application config with `provider = name` |
| neither | application config |

```java
AgentRequest.builder()
        .messages(List.of(UserMessageEntity.from("inspect this project")))
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

`baseUrl`, `apiKey` and `modelName` are marked `@NonNull` on `ModelConfig`, so a configuration missing
one of them fails as soon as it is built rather than at request time.
