package com.summit.core.runtime;

import java.io.Serializable;
import java.time.Duration;
import java.util.Map;

/** Description of a loop suspension. Payload is deliberately application-defined. */
public record SuspensionRequest(
        String id,
        String executionId,
        Serializable sessionId,
        String topic,
        Map<String, Object> payload,
        Duration timeout
) {
    public SuspensionRequest {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("suspension id is required");
        if (topic == null || topic.isBlank()) throw new IllegalArgumentException("suspension topic is required");
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        timeout = timeout == null ? Duration.ofMinutes(5) : timeout;
    }
}
