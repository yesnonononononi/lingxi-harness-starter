package com.summit.sandbox;

import lombok.Builder;

import java.io.Serializable;
import java.util.List;

/**
 * Literal filesystem paths (relative to the workspace) and SRT network rules.
 * restrictWrites defaults to true: writes must be confined to the backend's write allowlist.
 * Set it to false only for trusted processes allowed to use ambient filesystem write permissions.
 */
@Builder
public record SandboxPolicy(List<String> allowWrite, List<String> denyWrite, List<String> denyRead,
                            List<String> allowRead, List<String> allowedDomains, List<String> deniedDomains,
                            Boolean restrictWrites)
        implements Serializable {
    public SandboxPolicy {
        allowWrite = immutable(allowWrite);
        denyWrite = immutable(denyWrite);
        denyRead = immutable(denyRead);
        allowRead = immutable(allowRead);
        allowedDomains = immutable(allowedDomains);
        deniedDomains = immutable(deniedDomains);
        restrictWrites = restrictWrites == null ? Boolean.TRUE : restrictWrites;
    }

    private static List<String> immutable(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
