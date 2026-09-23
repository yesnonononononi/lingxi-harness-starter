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
