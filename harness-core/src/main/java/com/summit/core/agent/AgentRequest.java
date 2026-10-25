package com.summit.core.agent;


import com.summit.core.conf.ModelRetryConfig;
import com.summit.core.conf.SensitiveDataSpec;
import com.summit.core.conf.SkillConfig;
import com.summit.core.conversation.message.Message;
import com.summit.core.memory.MemoryConfig;
import com.summit.core.workspace.WorkspaceSpec;
import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;

import java.util.List;

@Data
@Builder
@Jacksonized
public class AgentRequest {
    /** Optional caller-provided identifier for this single execution. */
    private String executionId;

    /**
     * Complete context snapshot for this execution. This is the only source of conversation
     * history; the runtime never loads messages by an identifier. Images are carried by a
     * {@code UserMessageEntity} in this list.
     */
    @Builder.Default
    private List<Message> messages = List.of();
    /** The system prompt is given the LLM by the user as a starter */
    private String systemPrompt;
    /**
     * A high-level task definition, useful when a request is delegated by another agent. The
     * framework only carries it and renders it into the system prompt; it does not drive any
     * orchestration from it.
     */
    private List<String> task;
    /** Optional request-level whitelist of tool names. A {@code null} list keeps all registered tools unavailable; an empty list exposes no tools. Applications retain final authority over every model-visible capability. */
    private List<String> toolList;
    /** Optional managed workspace specification. When absent, the runtime uses the local process working directory. */
    private final WorkspaceSpec workspaceSpec;

    /** Optional per-execution memory access configuration; absent means no memory integration. */
    private MemoryConfig memoryConfig;
    /** Only the reference is persisted; the application resolves the exact MCP configuration revision. */
    private SensitiveDataSpec mcpConfigSpec;

    private SkillConfig skillConfig;

    @Builder.Default
    private AgentRuntimeParameters runtimeParameters = AgentRuntimeParameters.builder().build();

    /** Request-level model provider override. Only the provider implementation is swapped; every other attribute keeps the application value. Ignored when {@link #modelConfigSpec} is set. */
    private String modelProvider;

    /** Required model configuration revision when present; unresolved references never fall back to application defaults. */
    private SensitiveDataSpec modelConfigSpec;

    /** Absent or disabled settings leave framework retries off. */
    private ModelRetryConfig retryConfig;

    public AgentRuntimeParameters runtimeParametersOrDefault() {
        return runtimeParameters != null ? runtimeParameters : AgentRuntimeParameters.builder().build();
    }
}
