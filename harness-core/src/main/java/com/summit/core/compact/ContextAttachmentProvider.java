package com.summit.core.compact;

import java.io.Serializable;
import java.util.Optional;

/** Supplies application state that must survive context compaction. */
@FunctionalInterface
public interface ContextAttachmentProvider {
    ContextAttachmentProvider NONE = executionId -> Optional.empty();

    Optional<String> attachment(Serializable executionId);
}
