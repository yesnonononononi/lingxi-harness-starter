package com.summit.core.memory;

/** Reviews a proposed write before any storage mutation; the hook itself does not persist it. */
@FunctionalInterface
public interface MemoryHooker {
    MemoryDecision decide(MemoryContext context, MemoryChangeRequest changeRequest);
}
