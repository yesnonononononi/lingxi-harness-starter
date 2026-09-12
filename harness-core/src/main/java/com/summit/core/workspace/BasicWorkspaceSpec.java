package com.summit.core.workspace;

import java.util.Map;

/** Generic workspace specification useful for configuration and custom providers. */
public record BasicWorkspaceSpec(String provider, String workDir,
                                 Map<String, String> configuration) implements WorkspaceSpec {
    public BasicWorkspaceSpec {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("workspace provider must not be blank");
        }
        if (workDir == null || workDir.isBlank()) {
            throw new IllegalArgumentException("workspace workDir must not be blank");
        }
        configuration = configuration == null ? Map.of() : Map.copyOf(configuration);
    }

    public BasicWorkspaceSpec(String provider, String workDir) {
        this(provider, workDir, Map.of());
    }
}
