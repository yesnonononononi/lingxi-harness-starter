package com.summit.harnessexample.dto;

/**
 * Body of the user-choice decision endpoint
 * {@code POST /agent/choices/{toolExecutionId}/decide}: the option the user selected
 * for a waiting {@code require_choice} tool call.
 */
public record ChoiceDecisionRequest(String choice) {
}
