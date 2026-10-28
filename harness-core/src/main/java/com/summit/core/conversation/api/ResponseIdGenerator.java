package com.summit.core.conversation.api;

/** Generates positive decimal response IDs that can be ordered numerically. */
@FunctionalInterface
public interface ResponseIdGenerator {
    /**
     * Returns a unique canonical positive decimal long ID greater than {@code previousId} if supplied.
     * A null lower bound means this execution has no earlier invocation; generator state is retained.
     * The lower bound belongs to the execution and survives snapshot restore.
     */
    String nextId(String previousId);
}
