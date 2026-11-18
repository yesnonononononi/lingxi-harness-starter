package com.summit.core.conversation.event;

/**
 * Why one round's context differs from the previous one.
 *
 * <p>The list is a single source of truth on purpose: whether the cached prefix survived is
 * <em>derived</em> from it ({@link ContextChangedEvent#prefixPreserved()}), never carried as a
 * second independent flag. Two fields describing the same fact drift apart the first time one of
 * them is written on a new code path.</p>
 */
public enum ContextChangeReason {

    /**
     * The leading system prompt was written for the first time: the session had no prefix yet, so
     * nothing cached was lost.
     */
    SYSTEM_PROMPT_INSERTED,

    /**
     * An existing leading system prompt was replaced. The cached prefix (system prompt + tool
     * definitions) is gone; this is the expensive change.
     */
    SYSTEM_PROMPT_REPLACED,

    /**
     * The dynamic sections (MCP / skill / memory / task / workspace) changed. They are inserted
     * right before this round's user turn — behind the cached prefix — so the prefix survives.
     */
    DYNAMIC_SECTIONS_CHANGED
}
