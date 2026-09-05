package com.summit.runtime;

import com.summit.core.agent.Execution;
import com.summit.core.compact.ContextCompacter;
import com.summit.core.compact.ContextCompactRequest;
import com.summit.core.compact.ContextSqueezeRequest;
import com.summit.core.compact.Tokenizer;
import com.summit.core.conversation.ConversationManager;
import com.summit.core.runtime.*;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.compact.DefaultManualCompacter;
import com.summit.runtime.compact.DefaultModelCompacter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-execution check pointer.
 *
 * <p>A brand new instance is created inside the runtime factory for every
 * execution, therefore <b>all lifecycle state below is instance-scoped and must
 * never be {@code static}</b> — otherwise a stopped/paused execution would leak
 * into every other (and every later) execution running in the same JVM.</p>
 *
 * <p>The agent-loop thread is the only consumer of {@link LifeStyleCommandStore}
 * commands (at each loop checkpoint). While the loop is paused it therefore has
 * to keep watching the command store itself; a plain {@code await()} with no
 * external signal source would deadlock forever because RESUME/STOP enqueued by
 * another thread would never be consumed. We use a bounded {@code await()} and
 * poll the store again on every wake-up.</p>
 *
 * <p>Context-compaction responsibility split: this class only <b>judges the band</b> (against the
 * progressive thresholds — local truncation or model compaction). Once a band is hit, the actual
 * blocking compaction is delegated to the matching {@link ContextCompacter} implementation (manual and
 * model). By the time it returns, and before the main loop starts its next round, the session context
 * is ready.</p>
 */
@RequiredArgsConstructor
@Slf4j
public class RuntimeCheckPointer implements CheckPointer {

    /** How often a paused loop wakes up to re-check the command store. */
    private static final long PAUSE_POLL_INTERVAL_MS = 300L;

    /** Fallback truncation threshold when the policy is not configured (matches AgentConfig.OriginalSqueeze#defaultPolicy). */
    private static final double DEFAULT_TRUNCATE_THRESHOLD = 0.7;
    /** Fallback rounds per local truncation pass when the policy is not configured (matches AgentConfig.OriginalSqueeze#defaultPolicy). */
    private static final int DEFAULT_TRUNCATE_TURN = 5;
    /** Fallback model-squeeze threshold when the policy is not configured (matches AgentConfig.ModelSqueeze#defaultPolicy). */
    private static final double DEFAULT_MODEL_THRESHOLD = 0.85;

    private final LifeStyleHandler lifeStyleHandler;
    private final AgentConfig agentConfig;
    private final Tokenizer tokenizer;
    private final ConversationManager conversationManager;
    private final LifeStyleCommandStore lifeStyleCommandStore;
    /** Manual per-round truncation compaction (shouldSqueeze band). */
    private final DefaultManualCompacter manualCompacter;
    /** Model deep compaction (expectAdvanceSqueeze band). */
    private final DefaultModelCompacter modelCompacter;

    private volatile RuntimeLifeStyle currentStyle = RuntimeLifeStyle.RUNNING;
    private final ReentrantLock pausedLock = new ReentrantLock();
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final AtomicInteger currentStep = new AtomicInteger(1);
    private final Condition pausedCondition = pausedLock.newCondition();
    private volatile boolean paused = false;

    @Override
    public boolean beforeCheckpoint(Execution execution) {
        currentStep.incrementAndGet();
        drainCommands(execution);
        return shouldContinue(execution);
    }

    @Override
    public boolean afterCheckpoint(Execution execution) {
        drainCommands(execution);
        compactIfNeeded(execution);
        return shouldContinue(execution);
    }

    /**
     * Performs one blocking compaction for the progressive squeeze band: the manual compacter truncates
     * rounds when {@code shouldSqueeze}, or the model compacter summarizes and rebuilds the session when
     * {@code expectAdvanceSqueeze}. Returns once the compaction is done so the agent loop can start its
     * next round; does nothing when no band is hit.
     */
    private void compactIfNeeded(Execution execution) {
        ContextSqueezeRequest request = shouldSqueezeContext(conversationManager, execution);
        String band;
        ContextCompacter compacter;
        if (request.expectAdvanceSqueeze()) {
            band = "model";
            compacter = modelCompacter;
        } else if (request.shouldSqueeze()) {
            band = "manual";
            compacter = manualCompacter;
        } else {
            return;
        }
        if (compacter == null) {
            log.warn("【context-compact】no {} compacter wired, compression skipped: executionId={}",
                    band, execution.getId());
            return;
        }
        boolean compacted = compacter.compact(new ContextCompactRequest(
                execution.getSessionId(), execution.getId(), request));
        log.info("【context-compact】checkpoint triggered {} band, compacted={}, executionId={}",
                band, compacted, execution.getId());
    }

    private void drainCommands(Execution execution) {
        LoopCommand command;
        while ((command = lifeStyleCommandStore.poll()) != null) {
            applyCommand(command, execution);
        }
    }

    private void applyCommand(LoopCommand command, Execution execution) {
        switch (command) {
            case PAUSE -> {
                if (currentStyle == RuntimeLifeStyle.RUNNING) {
                    currentStyle = RuntimeLifeStyle.PENDING;
                    paused = true;
                    lifeStyleHandler.onPaused();
                    waitUntilResumedOrStopped(execution);
                    // stopped (or interrupted) while paused -> already cancelled inside the wait
                }
            }
            case STOP -> {
                stopRequested.set(true);
                if (currentStyle == RuntimeLifeStyle.RUNNING || currentStyle == RuntimeLifeStyle.PENDING) {
                    currentStyle = RuntimeLifeStyle.STOPPED;
                    paused = false;
                    wakeUpPausedLoop();
                    execution.cancel();
                    lifeStyleHandler.onStopped();
                }
            }
            case RESUME -> {
                if (currentStyle == RuntimeLifeStyle.PENDING) {
                    currentStyle = RuntimeLifeStyle.RUNNING;
                    paused = false;
                    wakeUpPausedLoop();
                    lifeStyleHandler.onResumed();
                }
            }
        }
    }

    /**
     * Blocks the agent-loop thread while the execution is paused. Wakes up on a
     * bounded interval to drain any RESUME / STOP command enqueued by another
     * thread, or immediately when the loop thread is interrupted.
     */
    private void waitUntilResumedOrStopped(Execution execution) {
        pausedLock.lock();
        try {
            while (paused && currentStyle == RuntimeLifeStyle.PENDING) {
                LoopCommand command = lifeStyleCommandStore.poll();
                if (command != null) {
                    applyCommand(command, execution);
                    continue;
                }
                try {
                    pausedCondition.await(PAUSE_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    stopRequested.set(true);
                    currentStyle = RuntimeLifeStyle.STOPPED;
                    paused = false;
                    execution.cancel();
                    lifeStyleHandler.onStopped();
                    break;
                }
            }
        } finally {
            pausedLock.unlock();
        }
    }

    private void wakeUpPausedLoop() {
        pausedLock.lock();
        try {
            pausedCondition.signalAll();
        } finally {
            pausedLock.unlock();
        }
    }

    private boolean shouldContinue(Execution execution) {
        return !stopRequested.get()
                && currentStyle.equals(RuntimeLifeStyle.RUNNING)
                && !tokenExhausted(execution, conversationManager)
                && currentStep.get() < effectiveMaxSteps(execution);
    }

    /**
     * Max loop iterations for this execution. {@code Execution.maxSteps} is not
     * set by {@code ExecutionCreator}, so fall back to the agent's configured
     * iteration budget; without any budget configured the loop is unbounded.
     */
    private int effectiveMaxSteps(Execution execution) {
        if (execution.getMaxSteps() > 0) {
            return execution.getMaxSteps();
        }
        Integer maxIterations = agentConfig.maxIterations();
        return maxIterations == null ? Integer.MAX_VALUE : Math.max(maxIterations, 1);
    }

    private boolean tokenExhausted(Execution execution, ConversationManager conversationManager) {
        Integer maxTokens = agentConfig.maxTokens();
        if (maxTokens == null) return true;
        int currentContextTokens = tokenizer.count(conversationManager.messages(execution.getSessionId()));
        return currentContextTokens >= maxTokens;
    }

    /**
     * Progressive squeeze decision based on {@link AgentConfig.ProgressiveSqueezePolicy}:
     * <ul>
     *   <li>ratio in [truncateThreshold, modelThreshold): local round-based truncation
     *       ({@code truncateSqueeze}) kicks in — the number of rounds squeezed per pass
     *       comes from {@code OriginalSqueeze.expectTruncateTurn};</li>
     *   <li>ratio &gt;= modelThreshold: local truncation stops and the model-based deep
     *       compaction ({@code DefaultModelCompacter}) is expected, i.e.
     *       {@code expectAdvanceSqueeze=true}.</li>
     * </ul>
     */
    public ContextSqueezeRequest shouldSqueezeContext(ConversationManager conversationManager, Execution execution) {

        double ratio = this.tokenizer.calcCurrentTokenRatio(conversationManager.messages(execution.getSessionId()), agentConfig.maxTokens());

        AgentConfig.ProgressiveSqueezePolicy policy = agentConfig.squeezeThreshold();
        Double truncateThreshold = policy == null || policy.truncateSqueeze() == null
                ? null : policy.truncateSqueeze().threshold();
        int truncateTurn = policy == null || policy.truncateSqueeze() == null
                ? 0 : Math.max(policy.truncateSqueeze().expectTruncateTurn(), 0);
        Double modelThreshold = policy == null || policy.modelSqueeze() == null
                ? null : policy.modelSqueeze().threshold();

        double original = truncateThreshold == null ? DEFAULT_TRUNCATE_THRESHOLD : truncateThreshold;
        double advanced = modelThreshold == null ? DEFAULT_MODEL_THRESHOLD : modelThreshold;
        boolean shouldTruncate = ratio >= original && ratio < advanced;
        return ContextSqueezeRequest.builder()
                .shouldSqueeze(shouldTruncate)
                .truncateTurn(shouldTruncate ? (truncateTurn > 0 ? truncateTurn : DEFAULT_TRUNCATE_TURN) : 0)
                .expectAdvanceSqueeze(ratio >= advanced)
                .build();
    }

}
