package com.summit.core.runtime;

import com.summit.core.runtime.workspace.OsType;
import com.summit.core.runtime.workspace.ShellType;
import com.summit.core.runtime.workspace.Workspace;
import lombok.Builder;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Static description of the environment a {@link Workspace} executes in.
 *
 * <p>{@code osType} / {@code shellType} tell the model how commands run,
 * {@code isolated} marks an environment whose toolchain lives inside the
 * workspace itself (a sandbox container) rather than on the host.</p>
 */
@Builder
public record RuntimeEnvironment(Charset charset,
                                 Map<String, String> envs,
                                 OsType osType,
                                 ShellType shellType,
                                 boolean isolated,
                                 String shellExecutable
) {
    public RuntimeEnvironment {
        envs = envs == null ? Map.of() : Map.copyOf(envs);
        if (shellExecutable != null && !Path.of(shellExecutable).isAbsolute()) {
            throw new IllegalArgumentException("shellExecutable must be an absolute path");
        }
    }

    public RuntimeEnvironment(Charset charset, Map<String, String> envs, OsType osType, ShellType shellType,
                              boolean isolated) {
        this(charset, envs, osType, shellType, isolated, null);
    }

    /** Keeps shell syntax in ShellType while allowing deployment-owned executable locations. */
    public List<String> buildCommand(String command) {
        List<String> argv = new ArrayList<>(Objects.requireNonNull(shellType, "shellType").buildCommand(command));
        if (shellExecutable != null) argv.set(0, shellExecutable);
        return List.copyOf(argv);
    }
}
