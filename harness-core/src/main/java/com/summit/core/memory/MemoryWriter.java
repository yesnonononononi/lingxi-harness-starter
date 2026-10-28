package com.summit.core.memory;

/** Replaceable persistence entry point for whole-document memory updates. */
@FunctionalInterface
public interface MemoryWriter {
    /**
     * Saves an approved proposal, after the runtime applies any hook replacement. Both runtime
     * and backend must enforce ALLOW_WRITE; the backend also checks principal/reference access.
     * Compare expectedVersion and replace content atomically: null permits create-if-absent only.
     * When operationId is supplied, retries of the same operation and payload must return its
     * original result; reuse with a different payload must be rejected. SAVED is returned only
     * after persistence succeeds. IO/service failures are exceptions, not successful results.
     */
    MemoryWriteResult save(MemoryContext context, MemoryChangeRequest changeRequest);
}
