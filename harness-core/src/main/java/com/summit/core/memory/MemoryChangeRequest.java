package com.summit.core.memory;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Proposal to replace the complete content of one memory document. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryChangeRequest {
    /** Document reference returned by the loader, or the intended reference for a new document. */
    private String reference;

    /** Stable identifier reused when retrying the same write; scoped by principal and reference. */
    private String operationId;

    /** Complete replacement text. Empty means clear the document; null is not a valid write. */
    private String newContent;

    /** Original complete text for business review; not a substitute for a version check. */
    private String oldContent;

    /**
     * Version returned by the loader. The backend compares it atomically when saving. Null means
     * create only if absent, never unconditional overwrite of an existing document.
     */
    @JsonAlias("version")
    private Integer expectedVersion;
}
