package com.summit.harnessexample;

import com.summit.harnessexample.common.Result;
import com.summit.harnessexample.dto.FileEditDecisionRequest;
import com.summit.harnessexample.service.FileEditService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * HTTP entry points for the file-edit accept/reject workflow.
 *
 * <p>Edits are already on disk when the user sees them; these endpoints mark an applied
 * edit as kept ({@code accept}) or restore the previous content ({@code reject}) — either a
 * single edit or a whole agent turn. The decision is broadcast to SSE clients
 * ({@code FILE_EDIT_DECISION}) so all open pages refresh their diff cards.</p>
 */
@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
public class FileEditController {

    private final FileEditService fileEditService;

    /** Pending (undecided) file edits of a session, newest first. */
    @GetMapping("/sessions/{sessionId}/edits")
    public Result<Map<String, Object>> pendingEdits(@PathVariable("sessionId") String sessionId) {
        return Result.ok(fileEditService.pending(sessionId));
    }

    /** One applied edit with its full old / new content, for the on-demand diff view. */
    @GetMapping("/sessions/{sessionId}/edits/{recordId}")
    public Result<Map<String, Object>> editDetail(@PathVariable("sessionId") String sessionId,
                                                  @PathVariable("recordId") String recordId) {
        return Result.ok(fileEditService.detail(sessionId, recordId));
    }

    /** Keeps a single applied edit. */
    @PostMapping("/edits/accept")
    public Result<Map<String, Object>> acceptEdit(@RequestBody(required = false) FileEditDecisionRequest request) {
        return Result.ok(fileEditService.decideEdit(sessionId(request), recordId(request), true));
    }

    /** Restores the previous content of a single applied edit. */
    @PostMapping("/edits/reject")
    public Result<Map<String, Object>> rejectEdit(@RequestBody(required = false) FileEditDecisionRequest request) {
        return Result.ok(fileEditService.decideEdit(sessionId(request), recordId(request), false));
    }

    /** Keeps every pending edit of one agent turn. */
    @PostMapping("/turns/accept")
    public Result<Map<String, Object>> acceptTurn(@RequestBody(required = false) FileEditDecisionRequest request) {
        return Result.ok(fileEditService.decideTurn(sessionId(request), turnId(request), true));
    }

    /** Restores every pending edit of one agent turn (highest version first). */
    @PostMapping("/turns/reject")
    public Result<Map<String, Object>> rejectTurn(@RequestBody(required = false) FileEditDecisionRequest request) {
        return Result.ok(fileEditService.decideTurn(sessionId(request), turnId(request), false));
    }

    private static String sessionId(FileEditDecisionRequest request) {
        return request == null ? null : request.sessionId();
    }

    private static String recordId(FileEditDecisionRequest request) {
        return request == null ? null : request.recordId();
    }

    private static String turnId(FileEditDecisionRequest request) {
        return request == null ? null : request.turnId();
    }
}
