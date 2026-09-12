package com.summit.harnessexample.dto;

import java.util.List;

/**
 * Body of {@code PUT /agent/sessions/{sessionId}/plans/{planId}/tasks/{taskId}}.
 *
 * <p>Only {@code version} (optimistic lock) and {@code tips} are used by the current UI: the plan
 * card no longer rewrites the step the model proposed, it only attaches / removes the
 * user-provided hint of that step. The full task surface is still accepted so the endpoint can
 * evolve without touching the contract.</p>
 */
public record TaskUpdateRequest(
        Long version,
        String title,
        String description,
        String acceptance,
        String status,
        List<String> dependencies,
        Integer priority,
        String tips
) {
}
