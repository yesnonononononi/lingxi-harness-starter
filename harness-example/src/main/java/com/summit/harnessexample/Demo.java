package com.summit.harnessexample;


import com.summit.harnesscore.agent.AgentRequest;
import com.summit.harnesscore.agent.Execution;
import com.summit.harnesscore.agent.ExecutionState;
import com.summit.harnesscore.exception.ModelException;
import com.summit.harnesscore.exception.NoSuchModelProviderException;
import com.summit.harnesscore.exception.OutWorkSpaceException;
import com.summit.harnesscore.runtime.Workspace;
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
    private final Workspace workspace;

    public void chat(String input) {
        chat(input, false, null);
    }

    public void chat(String input, boolean streaming) {
        chat(input, streaming, null);
    }

    public void chat(String input, boolean streaming, Serializable sessionId) {

        // 1. validate input
        if (input == null || input.isBlank()) {
            log.warn("chat input is null or blank");
            throw new IllegalArgumentException("chat input must not be null or blank");
        }
        log.info("chat input: {}, streaming: {}, sessionId: {}", input, streaming, sessionId);
        Execution execution;
        try {
            execution = defaultChatAgent.execute(AgentRequest
                    .builder()
                    .input(input)
                    .workspace(workspace)
                    .streaming(streaming)
                    .sessionId(sessionId)
                    .build()
            );
        } catch (NoSuchModelProviderException e) {
            log.error("model provider not found, please check the model configuration", e);
            throw e;
        } catch (ModelException e) {
            log.error("model invocation failed, please check the model service or network", e);
            throw e;
        } catch (OutWorkSpaceException e) {
            log.error("agent tried to access a path outside the workspace", e);
            throw e;
        } catch (IllegalArgumentException e) {
            log.error("invalid argument: {}", e.getMessage(), e);
            throw e;
        } catch (RuntimeException e) {
            log.error("unexpected runtime exception occurred while chatting with agent", e);
            throw e;
        } catch (Exception e) {
            log.error("unexpected exception occurred while chatting with agent", e);
            throw new IllegalStateException("chat with agent failed", e);
        }

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