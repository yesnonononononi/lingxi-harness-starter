package com.summit.harnesscore.conversation.event;

import lombok.Builder;

@Builder
public record AgentPartialTextEvent(String agentId, String executionId, String content) {
}
