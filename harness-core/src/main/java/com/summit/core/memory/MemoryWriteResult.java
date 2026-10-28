package com.summit.core.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Persistence outcome; conflict and rejection must not modify the stored document. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryWriteResult {
    public enum Status {
        SAVED,
        CONFLICT,
        REJECTED
    }

    private String reference;
    private Status status;

    /** New stored revision on SAVED, or the current revision on CONFLICT when available. */
    private Integer version;

    /** Optional explanation of a conflict or rejection. */
    private String message;
}
