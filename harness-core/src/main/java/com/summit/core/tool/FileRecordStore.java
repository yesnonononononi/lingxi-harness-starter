package com.summit.core.tool;

import lombok.NonNull;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence SPI for {@link FileRecord}s.
 *
 * <p>Implementers only provide the storage primitives ({@link #put}, {@link #get},
 * {@link #update}, {@link #removeById}, {@link #listBySession}, {@link #clearBySessionId},
 * {@link #clear}); the business-oriented queries below are {@code default} methods
 * built on {@link #listBySession} and may be overridden with native queries
 * (indexed SQL, Redis sets, ...) where the in-memory filtering would be wasteful.</p>
 *
 * <p>Identity and ordering are owned by the store, not by callers: {@link #put}
 * assigns the record id via {@link #generateId()} when absent, and assigns the
 * per-file {@code version} when absent. Callers therefore never generate ids
 * themselves.</p>
 */
public interface FileRecordStore {

    /**
     * Generates the id for records stored without one. The default is a random
     * UUID; persistent implementations may override it (auto-increment, Redis
     * INCR, ...) — override {@link #put} accordingly if the backend assigns
     * ids itself.
     */
    default Serializable generateId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Stores the record. When {@code record.id()} is {@code null} an id is
     * generated via {@link #generateId()}; when {@code record.version()} is
     * {@code null} the next per-file version (max version of the same
     * session + filePath, plus one) is assigned. Returns the stored record
     * carrying its final id/version.
     */
    FileRecord put(@NonNull FileRecord record);

    Optional<FileRecord> get(@NonNull Serializable sessionId, @NonNull Serializable recordId);

    /** Replaces the stored record (same session + id) with the given state/content. */
    void update(@NonNull FileRecord record);

    boolean removeById(@NonNull Serializable sessionId, @NonNull Serializable recordId);

    void clearBySessionId(@NonNull Serializable sessionId);

    /** All records of the session, in insertion order. */
    List<FileRecord> listBySession(@NonNull Serializable sessionId);

    void clear();


    /** All PENDING (applied, undecided) records of the session. */
    default List<FileRecord> listPending(@NonNull Serializable sessionId) {
        return this.listBySession(sessionId).stream()
                .filter(FileRecord::isPending)
                .toList();
    }

    /** All PENDING records of the session belonging to the given turn. */
    default List<FileRecord> listPendingByTurn(@NonNull Serializable sessionId, @NonNull Serializable turnId) {
        return this.listPending(sessionId).stream()
                .filter(r -> Objects.equals(r.turnId(), turnId))
                .toList();
    }

    /** All records of the session for the given file, in insertion order. */
    default List<FileRecord> listByFile(@NonNull Serializable sessionId, @NonNull String filePath) {
        return this.listBySession(sessionId).stream()
                .filter(r -> Objects.equals(r.filePath(), filePath))
                .toList();
    }
}
