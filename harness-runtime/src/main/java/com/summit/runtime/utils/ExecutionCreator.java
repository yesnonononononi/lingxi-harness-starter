package com.summit.runtime.utils;

import com.summit.core.agent.*;
import com.summit.core.conversation.message.Message;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ExecutionCreator {
    public static Execution create(AgentRequest agentRequest, Agent agent, boolean streaming){
        List<Message> messages = agentRequest.getMessages() == null
                ? new ArrayList<>()
                : new ArrayList<>(agentRequest.getMessages());
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("AgentRequest.messages must contain the conversation context");
        }

        return Execution.builder()
                .id(agentRequest.getExecutionId() == null || agentRequest.getExecutionId().isBlank()
                        ? UUID.randomUUID().toString() : agentRequest.getExecutionId())
                .agentId(agent.id())
                .agentRequest(agentRequest)
                .createAt(Instant.now())
                .executionState(ExecutionState.CREATED)
                .messages(messages)
                .streaming(streaming)
                .build();
    }

}
