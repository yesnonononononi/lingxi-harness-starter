package com.summit.core.compact;

import java.io.Serializable;
import java.util.Optional;

/** Supplies application state that must survive context compaction. */
@FunctionalInterface
public interface ContextAttachmentProvider {
    ContextAttachmentProvider NONE = sessionId -> Optional.empty();

    Optional<String> attachment(Serializable sessionId);
}
