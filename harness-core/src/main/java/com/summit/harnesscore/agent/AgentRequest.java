package com.summit.harnesscore.agent;


import com.summit.harnesscore.runtime.Workspace;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;

@Data
@Builder
public class AgentRequest {
    private @NonNull String input;
    private String systemPrompt;
    private @NonNull Workspace workspace ;
    private boolean thinking;
    private boolean streaming;
}
