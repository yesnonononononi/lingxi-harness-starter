package com.summit.harnesscore.runtime;

import java.nio.file.Path;

public interface Workspace {

    /**
     * Returns the unique identifier of this workspace.
     *
     * @return unique workspace identifier
     */
    String id();

    /**
     * Returns the runtime environment associated with this workspace.
     *
     * <p>The concrete workspace implementation determines how the
     * runtime environment is provided. It may represent a local
     * process environment, container, sandbox, or another execution
     * environment.</p>
     *
     * @return runtime environment
     */
    RuntimeEnvironment runtimeEnvironment();

    /**
     * Returns the working directory of this workspace.
     *
     * @return workspace working directory
     */
    String workDir();

    /**
     * Resolves the given path within this workspace.
     *
     * <p>The path is interpreted relative to the workspace's working
     * directory. The concrete implementation is responsible for
     * resolving the path and enforcing any workspace boundary or
     * security constraints.</p>
     *
     * @param path relative path
     * @return resolved path
     * @throws IllegalArgumentException if the path is invalid or
     *                                  cannot be resolved
     */
    Path resolve(String path);
}