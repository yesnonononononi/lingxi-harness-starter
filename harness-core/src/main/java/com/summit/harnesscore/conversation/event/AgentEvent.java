package com.summit.harnesscore.conversation.event;

import java.time.Instant;

public interface AgentEvent {
    String executionId();
    Instant timestamp();
}
