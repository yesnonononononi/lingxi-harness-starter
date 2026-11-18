package com.summit.core.conversation.event;

import com.summit.core.conversation.EventListener;

/**
 * Observers of what happens to an execution's context.
 *
 * <p>Separate from {@link com.summit.core.runtime.RuntimeListener} because the questions are
 * different: that one watches the run's lifecycle and tools, this one watches the message list the
 * model is about to be given. An application that renders streaming output has no business being
 * called when the memory section changed length.</p>
 */
public interface ContextListener extends EventListener {

    /** Failure policy of {@link #onContextChanged}. */
    default Behavior onContextChangedPolicy() {
        return Behavior.CONTAIN;
    }

    /** Failure policy of {@link #onContextUpdated}. */
    default Behavior onContextUpdatedPolicy() {
        return Behavior.CONTAIN;
    }

    /** Failure policy of {@link #onContextRebuilt}. */
    default Behavior onContextRebuiltPolicy() {
        return Behavior.CONTAIN;
    }

    /**
     * This round's context differs from the previous round's.
     *
     * <p>Check {@link ContextChangedEvent#prefixPreserved()} before assuming the cached prefix is
     * still valid.</p>
     */
    default void onContextChanged(ContextChangedEvent event) {
    }

    /** Context usage moved, or a compaction pass started / finished. Telemetry, published at once. */
    default void onContextUpdated(ContextUpdateEvent event) {
    }

    /** The context was rebuilt from a summary; the cached prefix is gone. */
    default void onContextRebuilt(ContextRebuiltEvent event) {
    }
}
