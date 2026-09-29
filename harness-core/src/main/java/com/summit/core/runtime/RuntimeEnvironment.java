package com.summit.core.runtime;

import com.summit.core.runtime.workspace.OsType;
import com.summit.core.runtime.workspace.ShellType;
import com.summit.core.runtime.workspace.Workspace;
import lombok.Builder;

import java.nio.charset.Charset;
import java.util.Map;

/**
 * Static description of the environment a {@link Workspace} executes in.
 *
 * <p>{@code osType} / {@code shellType} tell the model how commands run,
 * {@code isolated} marks an environment whose toolchain lives inside the
 * workspace itself (a sandbox container) rather than on the host.</p>
 */
@Builder
public record RuntimeEnvironment(Charset charset, Map<String, String> envs, OsType osType, ShellType shellType,
                                 boolean isolated) {
}
