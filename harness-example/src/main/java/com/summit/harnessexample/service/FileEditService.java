package com.summit.harnessexample.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.tool.DiffResult;
import com.summit.core.tool.FileRecord;
import com.summit.core.tool.FileRecordManager;
import com.summit.harnessexample.ActiveWorkspace;
import com.summit.harnessexample.SseEventPublisher;
import com.summit.harnessexample.common.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * File-edit accept / reject workflow.
 *
 * <p>Edits are already on disk when the user sees them; deciding marks an edit as kept
 * ({@code accept}) or physically restores the previous content ({@code reject}) — either a
 * single edit or a whole agent turn. Every decision is broadcast to SSE clients
 * ({@code FILE_EDIT_DECISION}) so all open pages refresh their diff cards.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileEditService {

    private final FileRecordManager fileRecordManager;
    private final ActiveWorkspace activeWorkspace;
    private final SseEventPublisher sseEventPublisher;
    private final ObjectMapper objectMapper;

    /** Pending (undecided) file edits of a session, newest first. */
    public Map<String, Object> pending(String sessionId) {
        List<Map<String, Object>> edits = new ArrayList<>();
        for (FileRecord record : fileRecordManager.listPendingRecords(sessionId)) {
            edits.add(toDto(record, false));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("edits", edits);
        return data;
    }

    /**
     * One record with its full old / new content, used by the front-end to render the
     * diff on demand when the user clicks an entry (kept out of {@link #pending} so the
     * list payload stays small).
     */
    public Map<String, Object> detail(String sessionId, String recordId) {
        requireText(sessionId, "sessionId");
        requireText(recordId, "recordId");
        FileRecord record = fileRecordManager.getRecord(sessionId, recordId)
                .orElseThrow(() -> ApiException.notFound("edit record not found: " + recordId));
        return toDto(record, true);
    }

    /** Keeps ({@code accept=true}) or restores a single applied edit. */
    public Map<String, Object> decideEdit(String sessionId, String recordId, boolean accept) {
        requireText(sessionId, "sessionId");
        requireText(recordId, "recordId");
        try {
            boolean handled = accept
                    ? fileRecordManager.accept(sessionId, recordId)
                    : fileRecordManager.reject(sessionId, recordId, activeWorkspace.get());
            if (!handled) {
                throw ApiException.notFound("edit record not found: " + recordId);
            }
            broadcastDecision(sessionId, recordId, null, accept ? "ACCEPTED" : "REJECTED", null);
            return Map.of("recordId", recordId, "decision", accept ? "ACCEPTED" : "REJECTED");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("failed to {} record {} of session {}: {}", accept ? "accept" : "reject", recordId, sessionId,
                    e.getMessage());
            throw ApiException.conflict(e.getMessage());
        }
    }

    /** Keeps or restores every pending edit of one agent turn (highest version first). */
    public Map<String, Object> decideTurn(String sessionId, String turnId, boolean accept) {
        requireText(sessionId, "sessionId");
        requireText(turnId, "turnId");
        try {
            int count = accept
                    ? fileRecordManager.acceptTurn(sessionId, turnId)
                    : fileRecordManager.rejectTurn(sessionId, turnId, activeWorkspace.get());
            broadcastDecision(sessionId, null, turnId, accept ? "ACCEPTED" : "REJECTED", count);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("turnId", turnId);
            data.put("decision", accept ? "ACCEPTED" : "REJECTED");
            data.put("count", count);
            return data;
        } catch (Exception e) {
            log.warn("failed to {} turn {} of session {}: {}", accept ? "accept" : "reject", turnId, sessionId,
                    e.getMessage());
            throw ApiException.conflict(e.getMessage());
        }
    }

    // ------------------------------------------------------------------ private

    private Map<String, Object> toDto(FileRecord record, boolean withContent) {
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
        if (withContent) {
            // Only the on-demand diff view needs the full texts; keep them out of the
            // list payload so a pending list never ships whole files twice over.
            dto.put("oldContent", record.oldContent());
            dto.put("newContent", record.newContent());
        }
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

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " must not be blank");
        }
    }
}
