package com.summit.core.runtime;

import java.io.Serializable;

/**
 * Per-session registry of lifecycle command stores.
 *
 * <p>Each execution binds a <b>brand-new</b> {@link LifeStyleCommandStore} to its
 * session id, so pause / resume / stop commands issued for one session (or one
 * execution) can never leak into another store/queue. A fresh store is created on
 * every registration and dropped again when the execution finishes.</p>
 */
public interface LifeStyleCommandRegistry {

    /**
     * Creates a brand-new command store, binds it to the given session and
     * returns it for the execution to use.
     */
    LifeStyleCommandStore register(Serializable sessionId);

    /**
     * Unbinds the session, but only if it is still bound to the given store
     * (a later execution of the same session may have already registered a
     * newer store, which must not be removed).
     */
    void unregister(Serializable sessionId, LifeStyleCommandStore store);

    /** Sends a pause command only to the given session's store. */
    void pause(Serializable sessionId);

    /** Sends a resume command only to the given session's store. */
    void resume(Serializable sessionId);

    /** Sends a stop command only to the given session's store. */
    void stop(Serializable sessionId);

    /** Sends a pause command to every registered store. */
    void pauseAll();

    /** Sends a resume command to every registered store. */
    void resumeAll();

    /** Sends a stop command to every registered store. */
    void stopAll();

    /** Number of currently registered sessions. */
    int size();
}
