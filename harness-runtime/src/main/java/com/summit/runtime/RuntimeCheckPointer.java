package com.summit.runtime;

import com.summit.core.agent.Execution;
import com.summit.core.compact.ContextSqueezeRequest;
import com.summit.core.compact.Tokenizer;
import com.summit.core.conversation.ConversationManager;
import com.summit.core.runtime.*;
import com.summit.runtime.agent.AgentConfig;
import lombok.RequiredArgsConstructor;

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
 * external signal source would dead-lock forever because RESUME/STOP enqueued by
 * another thread would never be consumed. We use a bounded {@code await()} and
 * poll the store again on every wake-up.</p>
 */
@RequiredArgsConstructor
public class RuntimeCheckPointer implements CheckPointer {

    /** How often a paused loop wakes up to re-check the command store. */
    private static final long PAUSE_POLL_INTERVAL_MS = 300L;

    private final LifeStyleHandler lifeStyleHandler;
    private final AgentConfig agentConfig;
    private final Tokenizer tokenizer;
    private final ConversationManager conversationManager;
    private final LifeStyleCommandStore lifeStyleCommandStore;

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
        ContextSqueezeRequest request;
        if ((request = shouldSqueezeContext(conversationManager, execution)).shouldSqueeze()) {
            this.conversationManager.squeezeContext(request.expectTokens(), null, execution.getSessionId());
        }
        return shouldContinue(execution);
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

    public ContextSqueezeRequest shouldSqueezeContext(ConversationManager conversationManager, Execution execution) {
        int currentTokens = tokenizer.count(conversationManager.messages(execution.getSessionId()));
        Double v = agentConfig.squeezeThreshold();
        Integer maxTokens = agentConfig.maxTokens();
        double expectT = maxTokens * (v > 1.0 ? 1.0 : v);
        return ContextSqueezeRequest.builder()
                .shouldSqueeze(currentTokens > expectT)
                .expectTokens((int) expectT)
                .build();
    }

}
