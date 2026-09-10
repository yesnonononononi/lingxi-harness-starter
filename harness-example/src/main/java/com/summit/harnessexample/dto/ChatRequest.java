package com.summit.harnessexample.dto;

/**
 * Body of {@code POST /agent/chat}.
 *
 * <p>Enum-like fields stay {@code String} on purpose: the service parses them leniently
 * (unknown value -&gt; null) so a newer front-end never breaks an older back-end.</p>
 */
public record ChatRequest(
        String input,
        Boolean streaming,
        String sessionId,
        String sessionName,
        String systemPrompt,
        String commandConfirmLevel,
        String loopBoundary
) {
}
