package com.summit.core.tool;

import lombok.NonNull;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence primitives for {@link FileRecord}s. Business rules (id/version
 * assignment, state transitions, conflict detection) live in
 * {@link FileRecordManager}, not here.
 */
public interface FileRecordStore {
    record SimpleRecord(
            Serializable recordId,
            String filePath,
            Integer plusLines,
            Integer minusLines
    ) {
    }

    void put(@NonNull FileRecord record);

    Optional<FileRecord> get(@NonNull Serializable sessionId, @NonNull Serializable recordId);

    /** Full records of the session, in insertion order. */
    List<FileRecord> listBySessionFull(@NonNull Serializable sessionId);

    /** Lightweight summaries of the session for list views. */
    Collection<SimpleRecord> listBySessionId(Serializable sessionId);

    /** Replaces the stored record (same session + id) with the given state/content. */
    void update(@NonNull FileRecord record);

    void clearBySessionId(@NonNull Serializable sessionId);

    boolean removeById(@NonNull Serializable sessionId, @NonNull Serializable recordId);

    Map<Serializable, List<FileRecord>> getAll();

    void clear();
}
