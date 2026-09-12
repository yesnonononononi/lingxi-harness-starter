```xml
    <dependency>
        <groupId>io.github.yesnonononononi</groupId>
        <artifactId>lingXi-harness-agent</artifactId>
        <version>1.0.3</version>
    </dependency>
```

## Workspace lifecycle prototype

`WorkspaceSpec` describes a desired environment, `WorkspaceRecord` is its persistable state,
and `WorkspaceManager` resolves live runtimes through registered `WorkspaceProvider` backends.
The starter supplies a local provider and an in-memory store by default. Docker support is
isolated in the optional `harness-sandbox-docker` module.

```java
WorkspaceRecord record = workspaceManager.create(
        new WorkspaceRef("project-42"),
        new BasicWorkspaceSpec("local", projectDirectory));

AgentRequest request = AgentRequest.builder()
        .input("inspect this project")
        .workspaceRef(record.ref())
        .build();
```

Managed conversations persist the `WorkspaceRef`, not the live bridge-bearing `Workspace`.
`ConversationManager.workspace(sessionId)` resolves the runtime lazily through the manager.
The live workspace field remains as a compatibility path for unmanaged legacy callers.
