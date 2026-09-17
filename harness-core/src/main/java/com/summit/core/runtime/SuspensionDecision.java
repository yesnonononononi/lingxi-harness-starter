package com.summit.core.runtime;

import java.util.Map;

/** Application-defined answer that resumes a suspended loop. */
public record SuspensionDecision(Status status, Map<String, Object> payload, String message) {

    public SuspensionDecision {
        if (status == null) throw new IllegalArgumentException("suspension status is required");
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public static SuspensionDecision resume(Map<String, Object> payload) {
        return new SuspensionDecision(Status.RESUMED, payload, null);
    }

    public static SuspensionDecision reject(String message) {
        return new SuspensionDecision(Status.REJECTED, Map.of(), message);
    }

    public enum Status {
        RESUMED,
        REJECTED,
        CANCELLED,
        TIMED_OUT
    }
}
