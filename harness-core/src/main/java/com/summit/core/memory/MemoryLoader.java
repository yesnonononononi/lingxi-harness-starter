package com.summit.core.memory;

/** Replaceable local-file or business-service memory loading entry point. */
@FunctionalInterface
public interface MemoryLoader {
    /**
     * Loads an authorized snapshot before the first model round. NONE must skip the backend.
     * A missing document is represented by null; denied access and IO/service failures must be
     * reported as exceptions rather than treated as absence. Runtime resume reuses the injected
     * snapshot instead of silently loading different content.
     */
    MemoryDocument load(MemoryContext memoryContext);
}
