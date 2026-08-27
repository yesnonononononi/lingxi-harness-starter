package com.summit.runtime.agent;

import com.summit.harnesscore.agent.*;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ExecutionCreator {
    public static Execution create(AgentRequest agentRequest, Agent agent){
        List<ChatMessage> messages = new ArrayList<>();
        String input = agentRequest.getInput();
        if (!input.isBlank()) {
            messages.add(UserMessage.from(input));
        }
        return Execution.builder()
                .id(UUID.randomUUID().toString())
                .agentId(agent.id())
                .agentRequest(agentRequest)
                .createAt(Instant.now())
                .executionState(ExecutionState.CREATED)
                .messages(messages)
                .thinking(agentRequest.isThinking())
                .streaming(agentRequest.isStreaming())
                .build();
    }
}
