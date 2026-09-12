package com.summit.core.workspace;

/** Defines whether the framework may destroy the resource behind a workspace. */
public enum ResourceOwnership {
    /** Resource was provisioned by the framework and may be destroyed explicitly. */
    MANAGED,
    /** Resource is supplied externally; the framework may use but never destroy it. */
    ATTACHED,
    /** Resource can be referenced by multiple workspaces and requires provider-specific cleanup. */
    SHARED
}
