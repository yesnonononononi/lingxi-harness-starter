package com.summit.harnessexample.dto;

/** Body of {@code POST /agent/chat}. */
public record ChatRequest(
        String input,
        String modelProvider,
        String sessionId,
        String sessionName,
        String systemPrompt,
        String commandApprovalPolicy
) {
}
