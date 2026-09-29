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

Every request carries its complete message context and a workspace. The starter includes the `local` workspace provider:

```java
import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.workspace.BasicWorkspaceSpec;
import com.summit.runtime.agent.DefaultChatAgent;
import java.util.List;

Execution execution = agent.execute(AgentRequest.builder()
        .messages(List.of(UserMessageEntity.from("Inspect this project")))
        .workspaceSpec(new BasicWorkspaceSpec("local", System.getProperty("user.dir")))
        .build());
```

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

MCP is disabled by default. When enabled, application-defined `McpProvider` beans are also
discovered and registered. Override the `McpClientFactory` bean to customize configured client
creation. Configured clients are closed on application shutdown; custom providers own their
connections. Invalid connection configuration fails startup; connection/discovery failures are
logged and skipped so local tools and other servers remain available. Restart to retry discovery
or apply changed configuration; automatic registry refresh is not implemented.

This initial integration keeps the existing conservative `SERIAL_MUTATION` policy for MCP tools.
The GitHub read-only header restricts the remote service, but does not mark the local tool definition
as `READ_ONLY`. Applications that filter tools by that flag must account for this before offering
MCP tools in a read-only Agent mode.
