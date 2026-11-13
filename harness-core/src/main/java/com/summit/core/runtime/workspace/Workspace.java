package com.summit.core.runtime.workspace;

import com.summit.core.exception.OutWorkSpaceException;
import com.summit.core.runtime.RuntimeEnvironment;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public interface Workspace {

    /** Returns the unique identifier of this workspace. */
    String id();

    /** Returns the runtime environment associated with this workspace. */
    RuntimeEnvironment runtimeEnvironment();

    /** Returns the working directory of this workspace. */
    String workDir();

    /** Resolves the given path within this workspace. */
    Path resolve(String path);

    /**
     * Resolves the given path and requires it to stay inside this workspace.
     *
     * <p>Geometric containment alone is not enough: a link inside the workspace can still point
     * anywhere else, so the real path is verified too. Tools that must not touch anything outside
     * the workspace call this; access modes that intentionally allow outside paths resolve plainly
     * with {@link #resolve(String)}.</p>
     *
     * @throws OutWorkSpaceException when the resolved path escapes this workspace
     */
    default Path resolveInside(String path) {
        Path resolved = resolve(path);
        if (!enclosesReal(resolved)) {
            throw new OutWorkSpaceException(
                    "path escapes the workspace: path=" + resolved + ", workDir=" + workDir());
        }
        return resolved;
    }

    /**
     * Whether the path stays inside this workspace even after links are resolved.
     *
     * <p>Targets that do not exist fall back to the geometric result: there is nothing to resolve
     * yet, and the following IO reports the missing file on its own.</p>
     */
    default boolean enclosesReal(Path path) {
        if (!encloses(path)) {
            return false;
        }
        try {
            Path real = path.toRealPath();
            if (encloses(real)) {
                return true;
            }
            // The workspace root itself may be a link, so compare against its real location as well.
            String workDir = workDir();
            return workDir != null && !workDir.isBlank() && real.startsWith(Paths.get(workDir).toRealPath());
        } catch (IOException notCreatedYet) {
            return true;
        }
    }

    /** Whether the given path lies inside this workspace. */
    default boolean encloses(Path path) {
        if (path == null) {
            return false;
        }
        String workDir = workDir();
        if (workDir == null || workDir.isBlank()) {
            return false;
        }
        Path root = Paths.get(workDir).toAbsolutePath().normalize();
        return path.toAbsolutePath().normalize().startsWith(root);
    }

    /** Returns the file and command capabilities for this workspace's runtime environment. */
    default WorkspaceBridge bridge() {
        return new LocalWorkspaceBridge(runtimeEnvironment());
    }
}
