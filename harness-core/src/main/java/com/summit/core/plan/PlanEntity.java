package com.summit.core.plan;

import com.summit.core.tool.LoopBoundary;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Session-scoped plan record persisted by {@link PlanStore}.
 *
 * <p>Both the <b>raw text</b> (as produced by the agent — used as the protection /
 * rebuild payload against context compression) and the <b>structured decision</b>
 * (parsed representation for front-end display) are stored together.</p>
 *
 * <p>{@link #state()} carries the <b>plan-level state machine</b>
 * (UN_APPROVED -&gt; APPROVED -&gt; COMPLETED, see {@link PlanState}), while each step
 * carries its own lifecycle status {@link PlanStepStatus}.</p>
 *
 * @param sessionId session the plan belongs to
 * @param text      the original full plan text as produced by the agent
 * @param decision  structured plan decision parsed from the text
 * @param boundary  loop boundary under which the plan was produced
 * @param state     plan-level approval / implementation state machine flag
 * @param createdAt plan creation time
 * @param updatedAt last modification time
 */
public record PlanEntity(
        Serializable sessionId,
        String text,
        PlanDecision decision,
        LoopBoundary boundary,
        PlanState state,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * Creates a new plan entity. A freshly captured plan always starts in
     * {@link PlanState#UN_APPROVED} — it must not be implemented before the user
     * approves it.
     *
     * @param sessionId session id
     * @param text      original plan text (verbatim)
     * @param decision  parsed structured decision
     * @param boundary  boundary under which the plan was produced
     */
    public static PlanEntity of(Serializable sessionId, String text, PlanDecision decision, LoopBoundary boundary) {
        Instant now = Instant.now();
        return new PlanEntity(sessionId, text, decision, boundary, PlanState.UN_APPROVED, now, now);
    }

    /**
     * Immutably transitions the plan-level state machine flag.
     *
     * @param state the new plan state (UN_APPROVED / APPROVED / COMPLETED)
     * @return a new plan entity with the given state and refreshed {@code updatedAt}
     */
    public PlanEntity withState(PlanState state) {
        return new PlanEntity(sessionId, text, decision, boundary, state, createdAt, Instant.now());
    }

    /**
     * Immutably appends an extra implementation step to the plan and re-syncs the
     * raw text, so both views stay consistent for the context-protection logic.
     */
    public PlanEntity appendStep(String description) {
        String stepId = UUID.randomUUID().toString();
        PlanStep newStep = new PlanStep(stepId, description, PlanStepStatus.PENDING);
        List<PlanStep> steps = new ArrayList<>(decision.steps());
        steps.add(newStep);
        PlanDecision newDecision = new PlanDecision(decision.title(), List.copyOf(steps));
        String appendedText = text == null || text.isBlank() ? description : text + "\n" + description;
        return new PlanEntity(sessionId, appendedText, newDecision, boundary, state, createdAt, Instant.now());
    }

    /**
     * Immutably transitions every step of the plan to the given status. Used by the
     * execution-time progress tracking (PENDING → IN_PROGRESS when the agent starts
     * implementing the plan, → COMPLETED when it finishes normally after running tools).
     * The raw {@code text} is intentionally left untouched so the original plan wording
     * survives for context-protection purposes. The plan-level {@code state} flag is
     * kept as-is.
     */
    public PlanEntity withStepsStatus(PlanStepStatus status) {
        List<PlanStep> steps = decision.steps().stream()
                .map(step -> new PlanStep(step.id(), step.description(), status))
                .toList();
        PlanDecision newDecision = new PlanDecision(decision.title(), steps);
        return new PlanEntity(sessionId, text, newDecision, boundary, state, createdAt, Instant.now());
    }
}
