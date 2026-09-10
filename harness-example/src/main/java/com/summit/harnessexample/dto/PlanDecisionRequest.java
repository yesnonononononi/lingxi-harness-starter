package com.summit.harnessexample.dto;

/**
 * Body of the plan decision endpoints:
 * {@code POST /agent/plans/{planId}/revise} reads {@code message},
 * {@code POST /agent/plans/{planId}/reject} reads {@code reason}.
 *
 * <p>Both are optional — an empty body means "change nothing, just decide".</p>
 */
public record PlanDecisionRequest(String message, String reason) {
}
