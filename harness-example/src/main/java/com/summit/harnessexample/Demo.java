package com.summit.harnessexample;


import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.AgentRuntimeParameters;
import com.summit.core.agent.Execution;
import com.summit.core.agent.ExecutionState;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.tool.CommandConfirmLevel;
import com.summit.core.tool.LoopBoundary;
import com.summit.runtime.agent.DefaultChatAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.io.Serializable;

@Slf4j
@RequiredArgsConstructor
@Component
public class Demo {


    private final DefaultChatAgent defaultChatAgent;

    /**
     * The workspace is caller-supplied (local or a per-project sandbox) and
     * passed straight into the AgentRequest — there is no default fallback.
     */
    public void chat(String input, String modelProvider, Serializable sessionId, String sessionName, Workspace workspace,
                     CommandConfirmLevel commandConfirmLevel,
                     @Nullable String systemPrompt, @Nullable LoopBoundary loopBoundary) {

        // 1. validate input
        if (input == null || input.isBlank()) {
            log.warn("chat input is null or blank");
            throw new IllegalArgumentException("chat input must not be null or blank");
        }
        if (workspace == null) {
            log.warn("chat workspace is null");
            throw new IllegalArgumentException("workspace must not be null: provide the workspace the agent should work in");
        }
        log.info("chat input: {}, modelProvider: {}, sessionId: {}, workspace: {}", input, modelProvider, sessionId, workspace.id());
        Execution execution;

        execution = defaultChatAgent.execute(AgentRequest
                .builder()
                .input(input)
                .workspace(workspace)
                .modelProvider(modelProvider)
                .sessionId(sessionId)
                .sessionName(sessionName)
                .systemPrompt(systemPrompt)
                .runtimeParameters(AgentRuntimeParameters.builder()
                        .confirmLevel(commandConfirmLevel)
                        .loopBoundary(loopBoundary)
                        .build())
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


    }

}
