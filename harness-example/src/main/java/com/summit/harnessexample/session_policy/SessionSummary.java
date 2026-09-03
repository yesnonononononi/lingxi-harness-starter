package com.summit.harnessexample.session_policy;

import java.io.Serializable;

/**
 * Lightweight session summary for list views, avoiding a full entity pull.
 * Example-scoped query-side DTO; the core {@code ConversationStore} SPI does
 * not expose listing capabilities.
 */
public record SessionSummary(Serializable sessionId, String sessionName) {
}
