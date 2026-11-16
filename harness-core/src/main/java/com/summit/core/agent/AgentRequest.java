package com.summit.core.agent;

import com.summit.core.conf.ModelRetryConfig;
import com.summit.core.conf.SensitiveDataSpec;
import com.summit.core.conf.SkillConfig;
import com.summit.core.model.image.ImageGenerationOptions;
import com.summit.core.workspace.WorkspaceOptions;
import com.summit.core.workspace.WorkspaceSpec;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;
import java.util.List;

@Data
@SuperBuilder
@Jacksonized
public class AgentRequest {
    /** Optional caller-provided identifier for this single execution. */
    private String executionId;

    /**
     * Complete context snapshot for this execution. This is the only source of conversation
     * history; the runtime never loads messages by an identifier. Images are carried by a
     * {@code UserMessageEntity} in {@link ContextSpec#getContext()}.
     */
    @Builder.Default
    private @NonNull ContextSpec contextSpec = ContextSpec.builder().build();
    /**
     * A high-level task definition, useful when a request is delegated by another agent. The
     * framework only carries it and renders it into the system prompt; it does not drive any
     * orchestration from it.
     */
    private List<String> task;
    /** Tool admission specification (static whitelist and dynamic per-round blacklist). */
    @Builder.Default
    private ToolSpec toolSpec = ToolSpec.builder().build();
    /** Optional managed workspace specification. When absent, the runtime uses the local process working directory. */
    private final WorkspaceSpec workspaceSpec;

    /** Volatile per-request workspace controls (access tier, environment variables), separate from static {@link #workspaceSpec}. */
    private WorkspaceOptions workspaceOptions;

    /** Only the reference is persisted; the application resolves the exact MCP configuration revision. */
    private SensitiveDataSpec mcpConfigSpec;

    private SkillConfig skillConfig;

    @Builder.Default
    private AgentRuntimeParameters runtimeParameters = AgentRuntimeParameters.builder().build();

    /** Request-level provider selection; the configured image provider uses its independent image defaults. Ignored when {@link #modelConfigSpec} is set. */
    private String modelProvider;

    /** Required model configuration revision when present; unresolved references never fall back to application defaults. */
    private SensitiveDataSpec modelConfigSpec;

    /** Per-call image specifications, inherited by each image invocation in this execution. */
    private ImageGenerationOptions imageGenerationOptions;

    /** Absent or disabled settings leave framework retries off. */
    private ModelRetryConfig retryConfig;

    public AgentRuntimeParameters runtimeParametersOrDefault() {
        return runtimeParameters != null ? runtimeParameters : AgentRuntimeParameters.builder().build();
    }

    public ToolSpec toolSpecOrDefault() {
        return toolSpec != null ? toolSpec : ToolSpec.builder().build();
    }
}
