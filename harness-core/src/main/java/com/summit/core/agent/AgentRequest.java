package com.summit.core.agent;


import com.summit.core.runtime.Workspace;
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
    private @NonNull String input;
    private String systemPrompt;
    private @NonNull Workspace workspace;
    private boolean streaming;
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
