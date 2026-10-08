# LingXi Harness Spring Boot Starter

## Dependency

```xml
<dependency>
    <groupId>io.github.yesnononononi</groupId>
    <artifactId>lingxi-harness-spring-boot-starter</artifactId>
    <version>1.1.0</version>
</dependency>
```

## Minimal configuration

The built-in provider supports OpenAI-compatible APIs. A basic agent needs only the chat endpoint,
API key and model name:

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

When `lingxi.agent.model.conf.compact` is absent, context compaction reuses the complete chat model
configuration. Configure `compact` only when compaction should use a different provider or model.

## Minimal execution

Every request carries its complete message context. The starter includes the `local` workspace provider;
when `workspaceSpec` is absent, execution uses the local process working directory (`user.dir`):

```java
import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.runtime.agent.DefaultChatAgent;
import java.util.List;

Execution execution = agent.execute(AgentRequest.builder()
        .messages(List.of(UserMessageEntity.from("Inspect this project")))
        .build());
```

To select another directory, set `.workspaceSpec(new BasicWorkspaceSpec("local", "your project directory"))`
with `com.summit.core.workspace.BasicWorkspaceSpec`. Docker requires the optional `harness-sandbox-docker`
dependency and an explicit `DockerWorkspaceSpec`.

No tools are enabled by default. Enable only the capabilities the application needs:

```yaml
lingxi:
  agent:
    runtime:
      tool:
        read-file:
          enabled: true
        edit-file:
          enabled: true
        terminal:
          enabled: true
```

Tool timeout, output limits, execution boundary and system prompt all have defaults.

## Skill resources

The default Skill loader scans a host-side directory for `SKILL.md` entries with `name` and
`description` in YAML frontmatter. Set the directory on each request and explicitly allow the
reader in the tool whitelist:

```java
AgentRequest.builder()
        .messages(List.of(UserMessageEntity.from("Review this project")))
        .skillConfig(new com.summit.core.conf.SkillConfig(java.nio.file.Path.of("D:/skills")))
        .toolList(List.of("read_skill"))
        .build();
```

The system prompt lists entry paths and explains how to load them. `read_skill` accepts a required
`path` and optional `name`, reads the entry or referenced text files, and returns their containing
directory for resolving further references. Relative paths start at the configured Skill root.
The default reader uses the host filesystem independently of the execution workspace and rejects
resources outside that root, including symbolic links that escape it. It does not execute scripts.

```yaml
lingxi:
  agent:
    runtime:
      tool:
        read-skill:
          enabled: true
          max-output: 20000
          timeout: 10s
```

These are the defaults. Setting `enabled: false` removes the reader; the application should then
omit Skill configuration from requests or provide its own reading capability. A custom
`SkillResolver` or `SkillLoader` bean replaces metadata parsing or discovery. For custom resource
storage, provide a `ToolDefinition` bean named `readSkillToolDefinition`, retaining the `read_skill`
name and `name`/`path` arguments, to replace the reader. Missing CommonMark does not prevent plain
requests from starting; Skill requests require a custom loader/resolver or that dependency.

## MCP: connect, discover and register

Enable MCP to create Streamable HTTP clients from configuration. After singleton initialization,
the starter discovers their tools and registers them in the existing `ToolRegistry` before the
application becomes ready. No separate Agent execution path is needed.

```yaml
lingxi:
  mcp:
    enabled: true
    servers:
      github:
        url: https://api.githubcopilot.com/mcp/
        headers:
          Authorization: "Bearer ${GITHUB_MCP_TOKEN}"
          X-MCP-Readonly: "true"
          X-MCP-Tools: "get_me,get_file_contents,pull_request_read"
        initialization-timeout: 15s
        execution-timeout: 60s
        max-output: 20000
```

Supply a GitHub PAT through `GITHUB_MCP_TOKEN`. The remote endpoint and headers are described in
the [GitHub MCP server configuration guide](https://github.com/github/github-mcp-server/blob/main/docs/server-configuration.md).
The token belongs to this application-wide connection; all agents granted these tools use its permissions.

Tools are registered as `github_get_me`, `github_get_file_contents`, etc. Add these names to an
Agent's `toolList` when it has an explicit whitelist. The original tool name is sent to the server.
`tool-name-prefix` overrides the default `<server-key>_` prefix; duplicate registered names fail
startup rather than overwrite local tools. Each server can be disabled with `enabled: false`.
`transport` defaults to `streamable-http`; other transports are not configured by this starter yet.

The SDK execution timeout defaults to 60 seconds. The runtime timeout is rounded up to whole
seconds with a further five-second guard. A timeout does not guarantee that a remote operation
was rolled back. Tool outputs use the existing runtime truncation, with a default budget of 20,000.

MCP is disabled by default. When enabled, application-defined `ScopeMcpProvider` beans are also
discovered and registered. Override the `McpClientFactory` bean to customize configured client
creation. Configured clients are closed on application shutdown; custom providers own their
connections. Invalid connection configuration fails startup; connection/discovery failures are
logged and skipped so local tools and other servers remain available. Restart to retry discovery
or apply changed configuration; automatic registry refresh is not implemented.

This initial integration keeps the existing conservative `SERIAL_MUTATION` policy for MCP tools.
The GitHub read-only header restricts the remote service, but does not mark the local tool definition
as `READ_ONLY`. Applications that filter tools by that flag must account for this before offering
MCP tools in a read-only Agent mode.
