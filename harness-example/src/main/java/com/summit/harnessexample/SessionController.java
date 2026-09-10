package com.summit.harnessexample;

import com.summit.harnessexample.common.Result;
import com.summit.harnessexample.dto.SessionIdRequest;
import com.summit.harnessexample.dto.SessionRenameRequest;
import com.summit.harnessexample.service.SessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Conversation management endpoints: listing, message history, rename and delete.
 */
@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;

    /** All persisted conversations (lightweight summaries). */
    @GetMapping("/sessions")
    public Result<Map<String, Object>> sessions() {
        return Result.ok(sessionService.list());
    }

    /**
     * Message history of one session, mapped to display DTOs (role: USER / AI / TOOL).
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public Result<Map<String, Object>> messages(@PathVariable("sessionId") String sessionId) {
        return Result.ok(sessionService.messages(sessionId));
    }

    /** Renames an existing conversation. */
    @PostMapping("/sessions/rename")
    public Result<Map<String, Object>> rename(@RequestBody(required = false) SessionRenameRequest request) {
        return Result.ok(sessionService.rename(
                request == null ? null : request.sessionId(),
                request == null ? null : request.sessionName()));
    }

    /** Deletes a conversation and its plan. */
    @PostMapping("/sessions/delete")
    public Result<Map<String, Object>> delete(@RequestBody(required = false) SessionIdRequest request) {
        return Result.ok(sessionService.delete(request == null ? null : request.sessionId()));
    }
}
