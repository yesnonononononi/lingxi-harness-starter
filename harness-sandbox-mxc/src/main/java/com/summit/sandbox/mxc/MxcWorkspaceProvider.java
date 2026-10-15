package com.summit.sandbox.mxc;

import com.summit.core.runtime.RuntimeEnvironment;
import com.summit.core.runtime.workspace.OsType;
import com.summit.core.runtime.workspace.ShellType;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.runtime.workspace.WorkspaceBridge;
import com.summit.core.workspace.WorkspaceProvider;
import com.summit.core.workspace.WorkspaceRef;
import com.summit.core.workspace.WorkspaceSpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Resolves an immutable MXC bridge after a real filesystem/namespace readiness probe. */
public final class MxcWorkspaceProvider implements WorkspaceProvider {
    public static final String TYPE = "mxc";
    private final MxcRuntime runtime;

    public MxcWorkspaceProvider(MxcRuntime runtime) { this.runtime = Objects.requireNonNull(runtime); }
    @Override public String type() { return TYPE; }

    @Override public Workspace resolve(WorkspaceSpec spec) {
        if (!(spec instanceof MxcWorkspaceSpec mxc)) throw new IllegalArgumentException("MXC requires an explicit MxcWorkspaceSpec");
        try {
            Path launcher = MxcHelpers.deploy(runtime.helperDirectory());
            MxcWorkspaceBridge bridge = new MxcWorkspaceBridge(runtime, launcher, mxc);
            bridge.verifyReady();
            String root = runtime.linuxPath(mxc.workDir());
            String id = WorkspaceRef.derived(TYPE, Objects.toString(mxc.scope(), "") + "|" + root).id();
            return new ResolvedWorkspace(id, root, bridge, runtime, mxc.environment());
        } catch (IOException e) { throw new IllegalStateException("MXC workspace readiness failed", e); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("MXC workspace readiness interrupted", e); }
    }

    private record ResolvedWorkspace(String id, String workDir, WorkspaceBridge bridge,
                                      MxcRuntime runtime, Map<String, String> environment) implements Workspace {
        @Override public RuntimeEnvironment runtimeEnvironment() {
            Map<String, String> env = new LinkedHashMap<>(environment);
            env.putIfAbsent("PATH", "/usr/bin:/bin");
            env.putIfAbsent("HOME", workDir);
            env.putIfAbsent("TMPDIR", "/tmp");
            env.putIfAbsent("LC_ALL", "C.UTF-8");
            env.put("HARNESS_WORKSPACE", workDir);
            // The absolute shell is Linux-side. Windows Path validation cannot represent /bin/bash.
            return RuntimeEnvironment.builder().charset(StandardCharsets.UTF_8).osType(OsType.LINUX)
                    .shellType(ShellType.BASH).isolated(true).envs(env).build();
        }

        @Override public Path resolve(String value) {
            if (value == null || value.isBlank()) return runtime.hostPath(workDir);
            if (value.startsWith("/") || value.matches("^[A-Za-z]:[\\\\/].*") || value.startsWith("\\\\")) {
                return runtime.hostPath(runtime.linuxPath(value));
            }
            return runtime.hostPath(MxcRuntime.linuxAbsolute(workDir + "/" + value.replace('\\', '/')));
        }

        @Override public boolean encloses(Path path) {
            if (path == null) return false;
            String candidate = runtime.linuxPath(path.toAbsolutePath().normalize().toString());
            return candidate.equals(workDir) || candidate.startsWith(workDir + "/");
        }
    }
}
