package com.summit.core.workspace;

import java.io.Serializable;
import java.util.Map;

/** Persistable workspace state. Live bridges and process handles must never be stored here. */
public record WorkspaceRecord(WorkspaceRef ref, WorkspaceSpec spec,
                              Map<String, String> providerState,
                              ResourceOwnership ownership) implements Serializable {
    public WorkspaceRecord {
        if (ref == null || spec == null) {
            throw new IllegalArgumentException("workspace ref and spec must not be null");
        }
        providerState = providerState == null ? Map.of() : Map.copyOf(providerState);
        ownership = ownership == null ? ResourceOwnership.MANAGED : ownership;
    }

    public WorkspaceRecord withProviderState(Map<String, String> state) {
        return new WorkspaceRecord(ref, spec, state, ownership);
    }
}
