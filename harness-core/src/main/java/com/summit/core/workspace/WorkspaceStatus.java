package com.summit.core.workspace;

/** Provider-neutral workspace health information. */
public record WorkspaceStatus(State state, String message) {
    public enum State { READY, STOPPED, MISSING, ERROR, UNKNOWN }

    public static WorkspaceStatus ready() {
        return new WorkspaceStatus(State.READY, null);
    }
}
