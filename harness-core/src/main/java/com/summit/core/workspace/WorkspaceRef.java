package com.summit.core.workspace;

import java.io.Serializable;

/** Stable, persistence-friendly reference to a workspace. */
public record WorkspaceRef(String id) implements Serializable {
    public WorkspaceRef {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("workspace id must not be blank");
        }
    }
}
