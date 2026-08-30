package com.summit.runtime.tool;

import com.summit.core.exception.FileModificationException;
import com.summit.core.runtime.Workspace;
import com.summit.core.tool.FileHasher;
import com.summit.core.tool.FileRecord;
import com.summit.core.tool.FileRecordManager;
import com.summit.core.tool.FileRecordStore;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Default {@link FileRecordManager}: applies the business rules (id/version
 * assignment, state transitions, hash-based conflict detection) on top of the
 * {@link FileRecordStore} and delegates the physical rollback to
 * {@link FileRecordRestorer}.
 *
 * <p>Public methods only orchestrate (load → decide → persist/restore); all
 * rules live in private helpers or {@link FileRecordRestorer}.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class DefaultFileRecordManager implements FileRecordManager {

    private final FileRecordStore store;
    private final FileRecordRestorer restorer;
    private final FileHasher fileHasher;

    @Override
    public Serializable record(@NonNull Serializable sessionId, @NonNull Serializable turnId,
                               @NonNull FileRecord draft) throws FileModificationException {
        FileRecord normalized = this.normalize(sessionId, turnId, draft);
        this.store.put(normalized);
        log.info("Recorded file edit {} (turn {}) on {} [v{}]",
                normalized.id(), turnId, normalized.filePath(), normalized.version());
        return normalized.id();
    }

    @Override
    public Optional<FileRecord> getRecord(@NonNull Serializable sessionId, @NonNull Serializable recordId) {
        return this.store.get(sessionId, recordId);
    }

    @Override
    public List<FileRecord> listPendingRecords(@NonNull Serializable sessionId) {
        return this.store.listBySessionFull(sessionId).stream()
                .filter(FileRecord::isPending)
                .toList();
    }

    @Override
    public boolean accept(@NonNull Serializable sessionId, @NonNull Serializable recordId)
            throws FileModificationException {
        FileRecord record = this.loadExisting(sessionId, recordId);
        FileRecord accepted = this.transition(record, FileRecord.State.ACCEPTED);
        this.store.update(accepted);
        log.info("Accepted file edit {} on {}", recordId, record.filePath());
        return true;
    }

    @Override
    public boolean reject(@NonNull Serializable sessionId, @NonNull Serializable recordId, @NonNull Workspace workspace)
            throws FileModificationException {
        FileRecord record = this.loadExisting(sessionId, recordId);
        FileRecord rejected = this.rollback(record, workspace);
        this.store.update(rejected);
        log.info("Rejected file edit {} on {}", recordId, record.filePath());
        return true;
    }

    @Override
    public int acceptTurn(@NonNull Serializable sessionId, @NonNull Serializable turnId)
            throws FileModificationException {
        int accepted = 0;
        for (FileRecord record : this.pendingOfTurn(sessionId, turnId)) {
            this.store.update(this.transition(record, FileRecord.State.ACCEPTED));
            accepted++;
        }
        log.info("Accepted turn {} of session {}: {} file edits", turnId, sessionId, accepted);
        return accepted;
    }

    @Override
    public int rejectTurn(@NonNull Serializable sessionId, @NonNull Serializable turnId, @NonNull Workspace workspace)
            throws FileModificationException {
        // Roll back per file from the newest edit to the oldest, so each
        // restore is applied on top of the state the edit actually produced.
        List<FileRecord> pending = this.pendingOfTurn(sessionId, turnId).stream()
                .sorted(Comparator.comparing(FileRecord::version).reversed())
                .toList();

        List<String> restored = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        for (FileRecord record : pending) {
            try {
                this.restorer.restore(record, workspace);
                this.store.update(this.transition(record, FileRecord.State.REJECTED));
                restored.add(record.filePath());
            } catch (FileModificationException e) {
                conflicts.add(e.getMessage());
            }
        }
        if (!conflicts.isEmpty()) {
            throw new FileModificationException(
                    "rejected " + restored.size() + " file edit(s) of turn " + turnId + ", but "
                            + conflicts.size() + " could not be restored: " + String.join("; ", conflicts));
        }
        log.info("Rejected turn {} of session {}: {} file edits", turnId, sessionId, restored.size());
        return restored.size();
    }

    @Override
    public boolean discardRecord(@NonNull Serializable sessionId, @NonNull Serializable recordId) {
        return this.store.removeById(sessionId, recordId);
    }

    @Override
    public void discardRecords(@NonNull Serializable sessionId) {
        this.store.clearBySessionId(sessionId);
    }

    private FileRecord normalize(Serializable sessionId, Serializable turnId, FileRecord draft)
            throws FileModificationException {
        if (draft.filePath() == null || draft.newContent() == null) {
            throw new FileModificationException("file record requires at least filePath and newContent");
        }
        return FileRecord.builder()
                .id(UUID.randomUUID().toString())
                .sessionId(sessionId)
                .turnId(turnId)
                .version(this.nextVersion(sessionId, draft.filePath()))
                .oldContent(draft.oldContent())
                .newContent(draft.newContent())
                .diff(draft.diff())
                .filePath(draft.filePath())
                .oldContentHash(draft.oldContent() == null ? null : this.fileHasher.hash(draft.oldContent()))
                .newContentHash(this.fileHasher.hash(draft.newContent()))
                .state(FileRecord.State.PENDING)
                .createAt(Instant.now())
                .build();
    }

    private int nextVersion(Serializable sessionId, String filePath) {
        return this.store.listBySessionFull(sessionId).stream()
                .filter(r -> Objects.equals(r.filePath(), filePath))
                .mapToInt(r -> r.version() == null ? 0 : r.version())
                .max().orElse(0) + 1;
    }

    private List<FileRecord> pendingOfTurn(Serializable sessionId, Serializable turnId) {
        return this.listPendingRecords(sessionId).stream()
                .filter(r -> Objects.equals(r.turnId(), turnId))
                .toList();
    }

    private FileRecord loadExisting(Serializable sessionId, Serializable recordId) {
        return this.store.get(sessionId, recordId).orElse(null);
    }

    private FileRecord transition(FileRecord record, FileRecord.State target) throws FileModificationException {
        return switch (record.state()) {
            case PENDING -> this.withState(record, target);
            case ACCEPTED -> target == FileRecord.State.ACCEPTED
                    ? record
                    : this.conflict(record, "already accepted");
            case REJECTED -> this.conflict(record, "already rejected");
        };
    }

    private FileRecord rollback(FileRecord record, Workspace workspace) throws FileModificationException {
        FileRecord rejected = this.transition(record, FileRecord.State.REJECTED);
        this.restorer.restore(record, workspace);
        return rejected;
    }

    private FileRecord withState(FileRecord record, FileRecord.State state) {
        return FileRecord.builder()
                .id(record.id()).sessionId(record.sessionId()).turnId(record.turnId())
                .version(record.version()).oldContent(record.oldContent()).newContent(record.newContent())
                .diff(record.diff()).filePath(record.filePath())
                .oldContentHash(record.oldContentHash()).newContentHash(record.newContentHash())
                .state(state).createAt(record.createAt())
                .build();
    }

    private FileRecord conflict(FileRecord record, String reason) throws FileModificationException {
        throw new FileModificationException(
                "record " + record.id() + " on " + record.filePath() + " is " + reason);
    }
}
