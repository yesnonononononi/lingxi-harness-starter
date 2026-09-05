package com.summit.runtime.plan;

import com.summit.core.conversation.event.PlanDecisionEvent;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.plan.PlanDecision;
import com.summit.core.plan.PlanEntity;
import com.summit.core.plan.PlanStepStatus;
import com.summit.core.plan.PlanStore;
import com.summit.core.tool.LoopBoundary;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.io.Serializable;
import java.util.Optional;

/**
 * Orchestrates the session plan lifecycle:
 * <ul>
 *   <li>{@link #capture} parses the raw plan text (the last pure-text AI message of a
 *       PLANNING execution) into a structured {@link PlanDecision}, upserts it into the
 *       session {@link PlanStore} and publishes a {@link PlanDecisionEvent};</li>
 *   <li>{@link #planOf} / {@link #appendStep} expose the stored plan to context
 *       protection (rebuild / squeeze) and execution-time step additions.</li>
 * </ul>
 *
 * <p>Keeping this logic in a dedicated component avoids polluting
 * {@code RuntimeProcessorTemplate} / {@code DefaultConversationManager}.</p>
 */
@RequiredArgsConstructor
public class PlanCoordinator {

    private final PlanStore planStore;
    private final PlanTextParser planTextParser;
    private final RuntimeEventPublisher runtimeEventPublisher;

    /**
     * Captures the plan text produced by a PLANNING execution as the session plan.
     * Parsing is tolerant and never throws; a blank text simply results in no plan.
     *
     * @return the parsed structured decision when captured
     */
    public Optional<PlanDecision> capture(Serializable sessionId, @Nullable String executionId,
                                          @Nullable String aiText, @Nullable LoopBoundary boundary) {
        if (sessionId == null || aiText == null || aiText.isBlank()) {
            return Optional.empty();
        }
        PlanDecision decision = planTextParser.parse(aiText);
        PlanEntity plan = PlanEntity.of(sessionId, aiText, decision, boundary);
        planStore.save(plan);
        runtimeEventPublisher.onPlanDecision(new PlanDecisionEvent(sessionId, executionId, decision));
        return Optional.of(decision);
    }

    public Optional<PlanEntity> planOf(Serializable sessionId) {
        return planStore.findBySession(sessionId);
    }

    public Optional<PlanEntity> appendStep(Serializable sessionId, String description) {
        return planStore.appendStep(sessionId, description);
    }

    /**
     * Transitions all steps of the session plan to the given status. Used when an
     * execution starts implementing the plan ({@code IN_PROGRESS}) or finishes it
     * normally after actually running tools ({@code COMPLETED}).
     */
    public Optional<PlanEntity> markSteps(Serializable sessionId, PlanStepStatus status) {
        return planStore.markSteps(sessionId, status);
    }
}
