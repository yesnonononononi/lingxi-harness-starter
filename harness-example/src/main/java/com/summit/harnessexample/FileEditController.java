package com.summit.harnessexample;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.runtime.Workspace;
import com.summit.core.tool.DiffResult;
import com.summit.core.tool.FileRecord;
import com.summit.core.tool.FileRecordManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * HTTP entry points for the file-edit accept/reject workflow.
 *
 * <p>Edits are already on disk when the user sees them; these endpoints mark
 * an applied edit as kept ({@code accept}) or physically restore the previous
 * content ({@code reject}) — either a single edit or a whole agent turn.
 * Every decision is broadcast to SSE clients ({@code FILE_EDIT_DECISION}) so
 * all open pages can refresh their diff cards.</p>
 */
@Slf4j
@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
public class FileEditController {

    private final FileRecordManager fileRecordManager;
    private final Workspace workspace;
    private final SseEventPublisher sseEventPublisher;
    private final ObjectMapper objectMapper;

    /** Pending (undecided) file edits of a session, newest first. */
    @GetMapping("/sessions/{sessionId}/edits")
    public ResponseEntity<Map<String, Object>> pendingEdits(@PathVariable("sessionId") String sessionId) {
        List<Map<String, Object>> edits = new ArrayList<>();
        for (FileRecord record : fileRecordManager.listPendingRecords(sessionId)) {
            edits.add(toDto(record));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("edits", edits);
        return ok(data);
    }

    /** Keeps a single applied edit. */
    @PostMapping("/edits/accept")
    public ResponseEntity<Map<String, Object>> acceptEdit(@RequestBody Map<String, String> body) {
        return decideEdit(body, true);
    }

    /** Restores the previous content of a single applied edit. */
    @PostMapping("/edits/reject")
    public ResponseEntity<Map<String, Object>> rejectEdit(@RequestBody Map<String, String> body) {
        return decideEdit(body, false);
    }

    /** Keeps every pending edit of one agent turn. */
    @PostMapping("/turns/accept")
    public ResponseEntity<Map<String, Object>> acceptTurn(@RequestBody Map<String, String> body) {
        return decideTurn(body, true);
    }

    /** Restores every pending edit of one agent turn (highest version first). */
    @PostMapping("/turns/reject")
    public ResponseEntity<Map<String, Object>> rejectTurn(@RequestBody Map<String, String> body) {
        return decideTurn(body, false);
    }

    // ------------------------------------------------------------------
    // orchestration only: resolve params -> call manager -> broadcast
    // ------------------------------------------------------------------

    private ResponseEntity<Map<String, Object>> decideEdit(Map<String, String> body, boolean accept) {
        String sessionId = body == null ? null : body.get("sessionId");
        String recordId = body == null ? null : body.get("recordId");
        if (isBlank(sessionId) || isBlank(recordId)) {
            return badRequest("sessionId and recordId must not be blank");
        }
        try {
            boolean handled = accept
                    ? fileRecordManager.accept(sessionId, recordId)
                    : fileRecordManager.reject(sessionId, recordId, workspace);
            if (!handled) {
                return notFound("edit record not found: " + recordId);
            }
            broadcastDecision(sessionId, recordId, null, accept ? "ACCEPTED" : "REJECTED", null);
            return ok(Map.of("recordId", recordId, "decision", accept ? "ACCEPTED" : "REJECTED"));
        } catch (Exception e) {
            log.warn("failed to {} record {} of session {}: {}", accept ? "accept" : "reject", recordId, sessionId, e.getMessage());
            return conflict(e.getMessage());
        }
    }

    private ResponseEntity<Map<String, Object>> decideTurn(Map<String, String> body, boolean accept) {
        String sessionId = body == null ? null : body.get("sessionId");
        String turnId = body == null ? null : body.get("turnId");
        if (isBlank(sessionId) || isBlank(turnId)) {
            return badRequest("sessionId and turnId must not be blank");
        }
        try {
            int count = accept
                    ? fileRecordManager.acceptTurn(sessionId, turnId)
                    : fileRecordManager.rejectTurn(sessionId, turnId, workspace);
            broadcastDecision(sessionId, null, turnId, accept ? "ACCEPTED" : "REJECTED", count);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("turnId", turnId);
            data.put("decision", accept ? "ACCEPTED" : "REJECTED");
            data.put("count", count);
            return ok(data);
        } catch (Exception e) {
            log.warn("failed to {} turn {} of session {}: {}", accept ? "accept" : "reject", turnId, sessionId, e.getMessage());
            return conflict(e.getMessage());
        }
    }

    private Map<String, Object> toDto(FileRecord record) {
        List<String> diffLines = record.diff() == null ? List.of() : List.of(record.diff().split("\n"));
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("recordId", record.id());
        dto.put("turnId", record.turnId());
        dto.put("filePath", record.filePath());
        dto.put("version", record.version());
        dto.put("diff", record.diff());
        dto.put("plusLines", DiffResult.countDiffLines(diffLines, '+'));
        dto.put("minusLines", DiffResult.countDiffLines(diffLines, '-'));
        dto.put("created", record.oldContent() == null);
        dto.put("state", record.state() == null ? null : record.state().name());
        return dto;
    }

    private void broadcastDecision(String sessionId, String recordId, String turnId, String decision, Integer count) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("recordId", recordId);
        data.put("turnId", turnId);
        data.put("decision", decision);
        if (count != null) {
            data.put("count", count);
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "FILE_EDIT_DECISION");
        event.put("data", data);
        try {
            sseEventPublisher.broadcast(objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            log.warn("failed to broadcast file edit decision", e);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 200);
        response.put("message", "ok");
        response.put("data", data);
        return ResponseEntity.ok(response);
    }

    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("code", 400, "message", message));
    }

    private ResponseEntity<Map<String, Object>> notFound(String message) {
        return ResponseEntity.status(404).body(Map.of("code", 404, "message", message));
    }

    private ResponseEntity<Map<String, Object>> conflict(String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", 409);
        error.put("message", message == null ? "decision failed" : message);
        return ResponseEntity.status(409).body(error);
    }
}
