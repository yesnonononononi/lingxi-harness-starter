package com.summit.runtime.utils;

import com.summit.core.agent.*;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.UserMessageEntity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ExecutionCreator {
    public static Execution create(AgentRequest agentRequest, Agent agent){
        List<Message> messages = new ArrayList<>();
        String input = agentRequest.getInput();
        if (!input.isBlank()) {
            messages.add(UserMessageEntity.from(input));
        }
        return Execution.builder()
                .id(UUID.randomUUID().toString())
                .agentId(agent.id())
                .sessionId(agentRequest.sessionIdOrDefault())
                .agentRequest(agentRequest)
                .createAt(Instant.now())
                .executionState(ExecutionState.CREATED)
                .messages(messages)
                .streaming(agentRequest.isStreaming())
                .loopBoundary(agentRequest.getLoopBoundary())
                .build();
    }
}
