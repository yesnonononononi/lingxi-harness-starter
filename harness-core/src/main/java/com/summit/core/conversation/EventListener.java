package com.summit.core.conversation;


import java.util.UUID;

public interface EventListener {
    /**
     * How the publisher treats a listener's failure for one event.
     *
     * <p>Containment is the default: one broken observer must not take the run down with it. A
     * listener that carries part of the run's own invariant answers
     * {@link Behavior#PROPAGATE} for that hook, and its failure then stops the dispatch — the
     * listeners after it never see the event.</p>
     */
    record Behavior(boolean catchErr) {
        /** Log the failure and continue with the remaining listeners. */
        public static final Behavior CONTAIN = new Behavior(true);
        /** Propagate the failure; the remaining listeners do not receive the event. */
        public static final Behavior PROPAGATE = new Behavior(false);
    }

    /**
     * Dispatch position among listeners of one publisher.
     *
     * <p>Deliberately abstract: a listener that never states where it belongs in the chain gets its
     * position from whoever registered it, which is exactly the ambiguity this ordering exists to
     * remove.</p>
     */
    int order();

    default String id(){
        return UUID.randomUUID().toString();
    }
}
