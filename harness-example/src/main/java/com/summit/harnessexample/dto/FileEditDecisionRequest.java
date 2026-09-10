package com.summit.harnessexample.dto;

/**
 * Body of the file-edit decision endpoints.
 *
 * <p>Single-edit decisions use {@code sessionId + recordId}; whole-turn decisions use
 * {@code sessionId + turnId}.</p>
 */
public record FileEditDecisionRequest(String sessionId, String recordId, String turnId) {
}
