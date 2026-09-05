package com.summit.core.tool;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Common "human-in-the-loop" gate shared by every approval point of the agent
 * runtime (command execution approval, plan approval, ...).
 *
 * <p>The runtime / agent-loop thread never busy-waits here: it performs a
 * <b>bounded, interruptible</b> wait via {@link #awaitDecision}. Any other
 * thread (typically an HTTP approve/reject endpoint) writes the human decision
 * through {@link #decide(CommandDecision)} to wake the waiter. Semantics are
 * identical for every use:
 * <ul>
 *   <li>APPROVE: the guarded action may go ahead;</li>
 *   <li>REJECT: the guarded action is cancelled and never executed;</li>
 *   <li>timeout / interruption: the caller treats it as an aborted approval.</li>
 * </ul>
 */
public abstract class AbstractApprovalGate {

    /** Completed means decided (APPROVE / REJECT); still pending until completed. */
    private final CompletableFuture<CommandDecision> decision = new CompletableFuture<>();

    /** Whether this gate is still awaiting the human decision. */
    public boolean isPending() {
        return !decision.isDone();
    }

    /** Returns the decision when decided, or {@code null} while still pending. */
    public CommandDecision getDecision() {
        return decision.isDone() ? decision.join() : null;
    }

    /**
     * Writes the decision (idempotent: only the first write takes effect).
     *
     * @return whether this call actually wrote the decision (false if the gate was already decided)
     */
    public boolean decide(CommandDecision d) {
        if (d == null) {
            return false;
        }
        return decision.complete(d);
    }

    /**
     * Waits for a decision with a bounded timeout and interruptible semantics.
     *
     * @return the decision; {@code null} if the wait timed out; throws
     *         {@link InterruptedException} when the thread is interrupted (callers
     *         must restore the interrupt flag and treat it as cancellation)
     */
    public CommandDecision awaitDecision(long timeout, TimeUnit unit) throws InterruptedException {
        try {
            return decision.get(timeout, unit);
        } catch (TimeoutException e) {
            return null;
        } catch (ExecutionException e) {
            // should never happen: gates only store plain enum values, never exceptions
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
