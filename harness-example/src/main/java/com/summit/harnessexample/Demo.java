package com.summit.harnessexample;


import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.AgentRuntimeParameters;
import com.summit.core.agent.Execution;
import com.summit.core.agent.ExecutionState;
import com.summit.core.conversation.message.Message;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.runtime.agent.DefaultChatAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
@Component
public class Demo {


    private final DefaultChatAgent defaultChatAgent;

    /** The workspace is caller-supplied (local or a per-project sandbox) and passed straight into the AgentRequest — there is no default fallback. */
    public Execution chat(List<Message> context, String executionId, String modelProvider, Workspace workspace,
                     CommandApprovalPolicy approvalPolicy,
                     @Nullable String systemPrompt) {

        // 1. validate input
        if (context == null || context.isEmpty()) {
            throw new IllegalArgumentException("context must contain at least one message");
        }
        if (workspace == null) {
            log.warn("chat workspace is null");
            throw new IllegalArgumentException("workspace must not be null: provide the workspace the agent should work in");
        }
        log.info("agent execution: {}, modelProvider: {}, workspace: {}", executionId, modelProvider, workspace.id());
        Execution execution;

        execution = defaultChatAgent.execute(AgentRequest
                .builder()
                .executionId(executionId)
                .messages(context)
                .workspace(workspace)
                .modelProvider(modelProvider)
                .systemPrompt(systemPrompt)
                .runtimeParameters(AgentRuntimeParameters.builder()
                        .attributes(CommandApprovalPolicy.toAttributes(approvalPolicy))
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
        return execution;
    }

}
