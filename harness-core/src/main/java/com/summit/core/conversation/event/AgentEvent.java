package com.summit.core.conversation.event;

import java.time.Instant;

public interface AgentEvent {
    String executionId();
    Instant timestamp();
}
