package com.summit.harnessexample;


import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.agent.ExecutionState;
import com.summit.core.runtime.Workspace;
import com.summit.runtime.agent.ChatAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.Serializable;

@Slf4j
@RequiredArgsConstructor
@Component
public class Demo {


    private final ChatAgent defaultChatAgent;

    /**
     * The workspace is caller-supplied (local or a per-project sandbox) and
     * passed straight into the AgentRequest — there is no default fallback.
     */
    public void chat(String input, boolean streaming, Serializable sessionId, String sessionName, Workspace workspace) {

        // 1. validate input
        if (input == null || input.isBlank()) {
            log.warn("chat input is null or blank");
            throw new IllegalArgumentException("chat input must not be null or blank");
        }
        if (workspace == null) {
            log.warn("chat workspace is null");
            throw new IllegalArgumentException("workspace must not be null: provide the workspace the agent should work in");
        }
        log.info("chat input: {}, streaming: {}, sessionId: {}, workspace: {}", input, streaming, sessionId, workspace.id());
        Execution execution;

        execution = defaultChatAgent.execute(AgentRequest
                .builder()
                .input(input)
                .workspace(workspace)
                .streaming(streaming)
                .sessionId(sessionId)
                .sessionName(sessionName)
                .build()
        );


        // 2. validate result
        if (execution == null) {
            log.error("agent execute returned null execution");
            throw new IllegalStateException("agent execute returned null execution");
        }
        if (execution.getExecutionState() == ExecutionState.FAILED) {
            log.warn("agent execution failed, state: {}, result: {}", execution.getExecutionState(), execution);
        }

        log.info("agent result: {}", execution);
    }

}
