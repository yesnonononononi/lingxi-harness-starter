package com.summit.core.conversation.message;

/**
 * Body span of one rendered dynamic-context section, offsets relative to the message text.
 *
 * <p>The span covers the body only: the {@code ## Section} heading in front of it stays out, so a
 * per-section figure can be compared with the body-only numbers the audit already reports.</p>
 */
public record ContextSectionMark(ContextSectionKind kind, int start, int length) {
}
