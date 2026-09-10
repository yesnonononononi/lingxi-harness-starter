package com.summit.harnessexample.dto;

/**
 * Body of {@code POST /agent/sessions/rename}.
 *
 * @param sessionId   session to rename (required)
 * @param sessionName new display name; blank keeps the current name
 */
public record SessionRenameRequest(String sessionId, String sessionName) {
}
