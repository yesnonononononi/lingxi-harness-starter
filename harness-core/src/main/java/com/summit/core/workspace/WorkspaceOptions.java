package com.summit.core.workspace;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.jackson.Jacksonized;

import java.util.Map;

/**
 * Volatile per-request workspace controls (access tier and environment variables),
 * separated from the static {@link WorkspaceSpec} so changing them does not invalidate
 * the cached system prompt prefix.
 */
@Data
@Builder
@Jacksonized
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceOptions {
    private String workDir;
    @Builder.Default
    private WorkspaceAccessMode accessMode = WorkspaceAccessMode.READ_WRITE;

    @Builder.Default
    private Map<String, String> environment = Map.of();
}
