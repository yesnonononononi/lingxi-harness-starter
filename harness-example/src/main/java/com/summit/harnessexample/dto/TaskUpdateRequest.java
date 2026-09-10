package com.summit.harnessexample.dto;

import java.util.List;

/**
 * Body of {@code PUT /agent/sessions/{sessionId}/plans/{planId}/tasks/{taskId}}.
 *
 * <p>Only {@code version} (optimistic lock) and {@code acceptance} / {@code description}
 * are used by the current UI, but the full task surface is accepted so the endpoint can
 * evolve without touching the contract.</p>
 */
public record TaskUpdateRequest(
        Long version,
        String title,
        String description,
        String acceptance,
        String status,
        List<String> dependencies,
        Integer priority
) {
}
