package com.summit.core.agent;


import com.summit.core.runtime.Workspace;
import com.summit.core.workspace.WorkspaceRef;
import com.summit.core.workspace.WorkspaceSpec;
import com.summit.core.tool.CommandConfirmLevel;
import com.summit.core.tool.LoopBoundary;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;

import java.io.Serializable;

@Data
@Builder
public class AgentRequest {
    public static final String DEFAULT_SESSION_ID = "default";

    private Serializable sessionId;

    private String sessionName;
    /**
     * The question is given the LLM
     */
    private @NonNull String input;
    /**
     * The system prompt is given the LLM by the user as a starter
     */
    private String systemPrompt;
    /** Existing live workspace; retained for source compatibility. */
    private Workspace workspace;
    /** Preferred reference to a workspace managed by the framework. */
    private WorkspaceRef workspaceRef;
    /** Creates and acquires a managed workspace when no reference exists yet. */
    private WorkspaceSpec workspaceSpec;
    /**
     * streaming output
     */
    private boolean streaming;
    /**
     * Plan & Auto determine the current model whether the model can edit files
     */
    @Builder.Default
    private LoopBoundary loopBoundary = LoopBoundary.EXECUTE;
    /**
     * Approval level for command-line tools; when {@code null} the executor
     * treats it as {@code FULL_ACCESS} (backward compatible).
     */
    private CommandConfirmLevel commandConfirmLevel;


    public Serializable sessionIdOrDefault() {
        return sessionId != null ? sessionId : DEFAULT_SESSION_ID;
    }
}
