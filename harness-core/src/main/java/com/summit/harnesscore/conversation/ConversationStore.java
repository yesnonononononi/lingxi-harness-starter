package com.summit.harnesscore.conversation;

import lombok.NonNull;

import java.io.Serializable;
import java.util.Collection;
import java.util.Optional;

/**
 * Persistence SPI for conversations. Query-side: callers (e.g. web controllers)
 * may depend on the store directly for listing / history reads;
 * {@link ConversationManager} remains the execution-side write facade.
 */
public interface ConversationStore {

    /**
     * Lightweight session summary for list views, avoiding a full entity pull.
     */
    record SessionSummary(Serializable sessionId, String sessionName) {
    }

    Optional<ConversationEntity> get(@NonNull Serializable sessionId);


    void save(@NonNull Serializable sessionId, @NonNull ConversationEntity conversation);

    default Optional<ConversationEntity> removeAndReturn(@NonNull Serializable sessionId){
        return Optional.empty();
    };

    void remove(@NonNull Serializable sessionId);

    void clear();

    Collection<ConversationEntity> list();

    /**
     * Returns a lightweight summary (id + name) of all stored sessions without
     * loading the full entities. Implementations should provide a cheap native
     * lookup (map keySet / values, Redis index set, DB key query, ...).
     *
     * @return summaries, never {@code null}; may be empty
     */
    Collection<SessionSummary> sessionSummaries();
}
