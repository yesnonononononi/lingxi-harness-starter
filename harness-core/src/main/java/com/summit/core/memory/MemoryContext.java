package com.summit.core.memory;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.summit.core.runtime.workspace.Workspace;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/** Per-execution access context shared by loaders, decision hooks and writers. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryContext {
    private MemoryConfig memoryConfig;

    /** Actual execution identifier, including identifiers allocated by the framework. */
    private String executionId;

    /** Live workspace for resolving paths and accessing its IO bridge; reattached after restore. */
    @JsonIgnore
    private Workspace workspace;

    /** Whether local memory IO may cross the workspace boundary; false unless explicitly granted. */
    private boolean allowOutsideWorkspace;

    /**
     * JSON-compatible access attributes supplied by the authenticated business layer. Backends
     * decide which keys identify a principal or tenant and must validate access to each reference.
     */
    @Builder.Default
    private Map<String, Object> attributes = Map.of();

    /** Optional task input for business recall; null means load the configured resource directly. */
    private String query;
}
