package com.summit.harnesscore.conversation.event;

import lombok.Builder;

import java.io.Serializable;

@Builder
public record AgentPartialThinkingEvent(
        Serializable sessionId,
        String agentId,
        String executionId,
        String content
) {

}
