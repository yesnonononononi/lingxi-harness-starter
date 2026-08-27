package com.summit.harnesscore.conversation.event;

import lombok.Builder;

@Builder
public record AgentPartialThinkingEvent(
        String agentId,
        String executionId,
        String content
) {

}
