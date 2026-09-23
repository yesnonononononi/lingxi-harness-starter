package com.summit.core.agent;


import com.summit.core.conf.ModelConfig;
import com.summit.core.conversation.message.Message;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.workspace.WorkspaceSpec;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;

import java.util.List;

@Data
@Builder
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
    /** A high-level task definition, useful when a request is delegated by another agent. */
    private String task;
    /** Optional request-level whitelist of tool names. A {@code null} list keeps all registered tools available; an empty list exposes no tools. Applications retain final authority over every model-visible capability. */
    private List<String> toolList;
    /** Creates and acquires a managed workspace when no reference exists yet. */
    private final  WorkspaceSpec workspaceSpec;
    /** reuse this workspace if workspaceSpec is null */
    private Workspace workspace;

    @Builder.Default
    private AgentRuntimeParameters runtimeParameters = AgentRuntimeParameters.builder().build();

    /** Request-level model provider override. Only the provider implementation is swapped; every other attribute keeps the application value. Ignored when {@link #modelConfig} is set. */
    private String modelProvider;

    /** Request-level model configuration. Used as is for this request — no field-level fallback to the application configuration. Takes precedence over {@link #modelProvider}. */
    private ModelConfig modelConfig;

    public AgentRuntimeParameters runtimeParametersOrDefault() {
        return runtimeParameters != null ? runtimeParameters : AgentRuntimeParameters.builder().build();
    }
}
