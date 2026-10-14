package com.summit.sandbox;

import lombok.Builder;

import java.io.Serializable;
import java.util.List;

/**
 * Literal filesystem paths (relative to the workspace) and SRT network rules.
 * SRT applies these rules using its platform backend; allowWrite is not a portable
 * guarantee that all other paths are unwritable. Applications own tool exposure.
 */
@Builder
public record SandboxPolicy(List<String> allowWrite, List<String> denyWrite, List<String> denyRead,
                            List<String> allowRead, List<String> allowedDomains, List<String> deniedDomains)
        implements Serializable {
    public SandboxPolicy {
        allowWrite = immutable(allowWrite);
        denyWrite = immutable(denyWrite);
        denyRead = immutable(denyRead);
        allowRead = immutable(allowRead);
        allowedDomains = immutable(allowedDomains);
        deniedDomains = immutable(deniedDomains);
    }

    private static List<String> immutable(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
