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
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Resolves an immutable MXC bridge after a real filesystem/namespace readiness probe. */
public final class MxcWorkspaceProvider implements WorkspaceProvider {
    public static final String TYPE = "sandbox";
    private final MxcRuntime runtime;

    public MxcWorkspaceProvider(MxcRuntime runtime) { this.runtime = Objects.requireNonNull(runtime); }
    @Override public String type() { return TYPE; }

    @Override public Workspace resolve(WorkspaceSpec spec) {
        if (!(spec instanceof MxcWorkspaceSpec mxc)) throw new IllegalArgumentException("MXC requires an explicit MxcWorkspaceSpec");
        try {
            Path launcher = MxcHelpers.deploy(runtime.helperDirectory());
            MxcWorkspaceBridge bridge = new MxcWorkspaceBridge(runtime, launcher, mxc);
            bridge.verifyReady();
            String root = runtime.nativePath(mxc.workDir());
            String id = WorkspaceRef.derived(TYPE, Objects.toString(mxc.scope(), "") + "|" + root).id();
            return new ResolvedWorkspace(id, root, bridge, runtime, mxc.environment(), bridge.commandCharset());
        } catch (IOException e) { throw new IllegalStateException("MXC workspace readiness failed", e); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("MXC workspace readiness interrupted", e); }
    }

    private record ResolvedWorkspace(String id, String workDir, WorkspaceBridge bridge,
                                      MxcRuntime runtime, Map<String, String> environment, Charset charset) implements Workspace {
        @Override public RuntimeEnvironment runtimeEnvironment() {
            boolean windows = runtime.windows();
            Map<String, String> env = new LinkedHashMap<>(MxcHostEnvironment.resolve(environment, runtime));
            env.putIfAbsent("PATH", windows
                    ? Path.of(runtime.nodeExecutable()).getParent() + ";" + System.getenv("SystemRoot") + "\\System32"
                    : "/usr/bin:/bin");
            env.putIfAbsent("HOME", workDir);
            if (windows) {
                env.putIfAbsent("USERPROFILE", workDir);
                env.putIfAbsent("LOCALAPPDATA", workDir);
                env.putIfAbsent("TEMP", workDir);
                env.putIfAbsent("TMP", workDir);
            } else {
                env.putIfAbsent("TMPDIR", "/tmp");
                env.putIfAbsent("LC_ALL", "C.UTF-8");
            }
            env.put("WORKDIR", workDir);
            return RuntimeEnvironment.builder().charset(charset).osType(windows ? OsType.WINDOWS : OsType.LINUX)
                    .shellType(windows ? ShellType.CMD : ShellType.BASH).isolated(true).envs(env).build();
        }

        @Override public Path resolve(String value) {
            if (value == null || value.isBlank()) return runtime.hostPath(workDir);
            Path target = Path.of(value);
            return runtime.hostPath((target.isAbsolute() ? target : Path.of(workDir).resolve(target)).normalize().toString());
        }

        @Override public boolean encloses(Path path) {
            if (path == null) return false;
            return path.toAbsolutePath().normalize().startsWith(Path.of(workDir));
        }
    }
}
