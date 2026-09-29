package com.summit.core.runtime.workspace;

import com.summit.core.runtime.RuntimeEnvironment;

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

    /** Returns the IO / command-execution bridge for this workspace. */
    default WorkspaceBridge bridge() {
        return LocalWorkspaceBridge.INSTANCE;
    }
}