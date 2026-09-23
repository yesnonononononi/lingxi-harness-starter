package com.summit.core.agent;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/** Per-run controls for an agent request. */
@Data
@Builder
public class AgentRuntimeParameters {

    /** Opaque per-run attributes for application policies. The runtime only carries them and never inspects a key. */
    @Builder.Default
    private Map<String, Object> attributes = Map.of();

    /** Whether this run may operate outside the workspace root (read or write paths that {@code Workspace#encloses(Path)} reports as outside). */
    @Builder.Default
    private boolean allowOutsideWorkspace = false;


}
