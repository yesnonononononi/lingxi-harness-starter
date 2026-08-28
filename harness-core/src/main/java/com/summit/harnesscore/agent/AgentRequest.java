package com.summit.harnesscore.agent;


import com.summit.harnesscore.runtime.Workspace;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;

import java.io.Serializable;

@Data
@Builder
public class AgentRequest {
    public static final String DEFAULT_SESSION_ID = "default";

    private Serializable sessionId;
    private @NonNull String input;
    private String systemPrompt;
    private @NonNull Workspace workspace ;
    private boolean thinking;
    private boolean streaming;

    public Serializable sessionIdOrDefault() {
        return sessionId != null ? sessionId : DEFAULT_SESSION_ID;
    }
}
