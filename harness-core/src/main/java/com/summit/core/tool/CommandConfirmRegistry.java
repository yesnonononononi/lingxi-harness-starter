package com.summit.core.tool;

/**
 * Registry of commands awaiting human approval, indexed by toolExecutionId.
 *
 * <p>Implementations must be thread-safe, since it is used concurrently by three threads:</p>
 * <ul>
 *   <li>tool execution thread: {@link #register(String, String)} when suspending, {@link #get(String)} before re-executing after approval;</li>
 *   <li>HTTP/host thread: {@link #decide(String, CommandDecision)} writes the approval decision;</li>
 *   <li>agent loop thread: {@link #unregister(String)} once approval has finished (approved / rejected / timed out / interrupted).</li>
 * </ul>
 */
public interface CommandConfirmRegistry {

    /**
     * Registers (or reuses) the gate for the given toolExecutionId.
     *
     * @return the existing or newly created gate; a toolExecutionId always maps to a single gate
     */
    CommandConfirmGate register(String toolExecutionId, String command);

    CommandConfirmGate get(String toolExecutionId);

    /**
     * Writes an approval decision.
     *
     * @return true if the decision was written; false if the gate is absent or already decided
     */
    boolean decide(String toolExecutionId, CommandDecision decision);

    /** Removes the gate (approval finished / timed out / session ended). */
    void unregister(String toolExecutionId);

    /** Number of commands currently awaiting approval. */
    int size();
}
