package com.summit.harnessexample;

import com.summit.core.runtime.Workspace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Holds the single active {@link Workspace} of the application so that
 * controllers can resolve the "current" sandbox without being re-wired.
 *
 * <p>At startup the active workspace is the default bean created by
 * {@link WorkspaceConfig} (docker sandbox or local). When the user selects
 * another workspace folder, {@link WorkspaceSandboxService} swaps in a new
 * workspace through {@link #swap(Workspace)} and every subsequent request —
 * chat, file-edit restore, workdir queries — transparently targets the new
 * sandbox ("global switch").</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActiveWorkspace {

    /** The default workspace created at startup (docker sandbox or local). */
    private final Workspace initial;

    /** The currently active workspace; {@code null} until first swapped. */
    private volatile Workspace current;

    /**
     * Returns the currently active workspace, falling back to the startup default.
     */
    public Workspace get() {
        Workspace active = current;
        return active == null ? initial : active;
    }

    /**
     * Replaces the active workspace. All callers of {@link #get()} observe the
     * new workspace from this point on.
     *
     * @param workspace the new active workspace
     * @return the previously active workspace
     */
    public synchronized Workspace swap(Workspace workspace) {
        if (workspace == null) {
            throw new IllegalArgumentException("workspace must not be null");
        }
        Workspace previous = get();
        current = workspace;
        log.info("active workspace switched: {} -> {} (workdir {})",
                describe(previous), workspace.id(), workspace.workDir());
        return previous;
    }

    private String describe(Workspace workspace) {
        return workspace == null ? "null" : workspace.id() + "@" + workspace.workDir();
    }
}
