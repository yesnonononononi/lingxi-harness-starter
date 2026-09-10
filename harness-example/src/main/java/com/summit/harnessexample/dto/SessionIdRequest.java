package com.summit.harnessexample.dto;

/**
 * Body carrying a single session id, used by {@code POST /agent/sessions/delete}.
 */
public record SessionIdRequest(String sessionId) {
}
