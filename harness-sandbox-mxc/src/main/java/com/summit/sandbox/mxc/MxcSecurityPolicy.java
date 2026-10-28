package com.summit.sandbox.mxc;

import java.util.List;
import java.util.Objects;

/** Native path denial and explicit host-volume access; never a tool-only guard. */
public record MxcSecurityPolicy(List<String> deniedPaths, List<String> ambientRoots, boolean allowOutboundNetwork) {
    public static final MxcSecurityPolicy WORKSPACE_ONLY = new MxcSecurityPolicy(List.of(), List.of(), false);
    public MxcSecurityPolicy {
        deniedPaths = List.copyOf(Objects.requireNonNull(deniedPaths, "deniedPaths"));
        ambientRoots = List.copyOf(Objects.requireNonNull(ambientRoots, "ambientRoots"));
    }
}
