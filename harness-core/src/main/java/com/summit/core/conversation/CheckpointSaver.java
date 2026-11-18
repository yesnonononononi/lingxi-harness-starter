package com.summit.core.conversation;

import com.summit.core.agent.Execution;

/**
 * Persists a checkpoint of one execution, handed to the manager by whoever can actually write it.
 *
 * <p>Context facts published by a {@link ConversationManager} — the change of this round's message
 * list and the rebuild from a summary — are meant to be read as authoritative: the session they
 * describe has already been saved when a listener sees them. That ordering is the manager's
 * sequencing decision, but persistence is not the manager's business: the repository, its
 * transaction boundary and the order of {@code save} and {@code unregister} belong to the runtime.
 * This interface is the seam between the two — the caller passes the save action in, the manager
 * calls it at the one point that matters, immediately before publishing.</p>
 *
 * <p>The manager calls it only when it is about to publish something. A round whose context did not
 * change publishes nothing and therefore saves nothing on this path: there is no fact to back, and
 * a caller that needs a snapshot of an unchanged round saves on its own.</p>
 *
 * <p><b>Implementations must be synchronous.</b> {@code save} has to complete — or throw — before it
 * returns. "Persist it in the background" defeats the whole contract: the event would reach a
 * consumer before the snapshot exists, which is exactly the ordering this seam was introduced to
 * rule out. An implementation that throws is telling the truth: the manager will not publish the
 * fact, and the exception propagates to whoever called {@code startConversation} /
 * {@code rebuildContext}.</p>
 */
@FunctionalInterface
public interface CheckpointSaver {

    /**
     * Persists the execution's current state, synchronously.
     *
     * @param execution the session whose checkpoint must exist before the next fact is published
     */
    void save(Execution execution);

    /**
     * Saves nothing — correct only where nothing is persisted at all.
     *
     * <p>For in-process tests, one-off scripts and assemblies that deliberately keep no store. It is
     * <em>not</em> a production default: with it, a published fact has no saved session behind it,
     * and the authoritative reading the contract promises is simply false. Production wiring passes a
     * real save, and an assembly that has no repository should hand over a saver that says so rather
     * than silently doing nothing — which is why this constant lives here, visible at the call site,
     * instead of being the fallback of a missing argument.</p>
     */
    CheckpointSaver NONE = execution -> {
        // deliberately empty: see the javadoc above for what it means and when it is wrong
    };
}
