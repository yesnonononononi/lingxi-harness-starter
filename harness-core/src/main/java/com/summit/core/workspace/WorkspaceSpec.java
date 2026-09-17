package com.summit.core.workspace;

import java.io.Serializable;
import java.util.Map;

/** Desired workspace configuration, independent from a live runtime resource. */
public interface WorkspaceSpec extends Serializable {
    /** Existing workspace to acquire; {@code null} means create one for this execution. */
    default WorkspaceRef workspaceRef() {
        return null;
    }

    String provider();

    String workDir();

    default Map<String, String> configuration() {
        return Map.of();
    }
}
