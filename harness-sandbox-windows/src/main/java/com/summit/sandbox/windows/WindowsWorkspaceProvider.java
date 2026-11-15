package com.summit.sandbox.windows;

import com.summit.core.runtime.RuntimeEnvironment;
import com.summit.core.runtime.workspace.OsType;
import com.summit.core.runtime.workspace.ShellType;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.runtime.workspace.WorkspaceBridge;
import com.summit.core.workspace.WorkspaceProvider;
import com.summit.core.workspace.WorkspaceRef;
import com.summit.core.workspace.WorkspaceSpec;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * {@link WorkspaceProvider} implementation backed by dual Windows sandbox accounts
 * ({@value WindowsSandboxAccount#READ_ONLY_USERNAME} and {@value WindowsSandboxAccount#READ_WRITE_USERNAME}).
 */
public final class WindowsWorkspaceProvider implements WorkspaceProvider {
    public static final String TYPE = "windows-sandbox";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Workspace resolve(WorkspaceSpec spec) {
        if (!(spec instanceof WindowsWorkspaceSpec winSpec)) {
            throw new IllegalArgumentException("WindowsWorkspaceProvider requires a WindowsWorkspaceSpec");
        }
        if (!WindowsSandboxProvisioner.isProvisioned()) {
            throw new IllegalStateException(
                    "Windows sandbox accounts are not provisioned. Call WindowsSandboxProvisioner.provisionIfNeeded() first.");
        }
        Path root = Path.of(winSpec.workDir()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Workspace directory does not exist: " + root);
        }

        SandboxMode mode = winSpec.writable() ? SandboxMode.READ_WRITE : SandboxMode.READ_ONLY;
        WindowsWorkspaceAcl.ensureWorkspacePermission(root, WindowsSandboxAccount.usernameFor(mode), mode);

        WindowsWorkspaceBridge bridge = new WindowsWorkspaceBridge(root, mode, winSpec.environment());
        String rootStr = root.toString();
        String id = WorkspaceRef.derived(TYPE, winSpec.scope() + "|" + rootStr + "|" + mode.name()).id();
        return new ResolvedWorkspace(id, rootStr, bridge, winSpec.environment());
    }

    private record ResolvedWorkspace(
            String id,
            String workDir,
            WorkspaceBridge bridge,
            Map<String, String> environment
    ) implements Workspace {
        @Override
        public RuntimeEnvironment runtimeEnvironment() {
            Map<String, String> env = new LinkedHashMap<>(environment);
            String sysRoot = Objects.requireNonNullElse(System.getenv("SystemRoot"), "C:\\Windows");
            env.putIfAbsent("PATH", sysRoot + "\\System32;" + sysRoot);
            env.putIfAbsent("HOME", workDir);
            env.putIfAbsent("USERPROFILE", workDir);
            env.putIfAbsent("LOCALAPPDATA", workDir);
            env.putIfAbsent("TEMP", workDir);
            env.putIfAbsent("TMP", workDir);
            env.put("WORKDIR", workDir);
            return RuntimeEnvironment.builder()
                    .charset(Charset.defaultCharset())
                    .osType(OsType.WINDOWS)
                    .shellType(ShellType.CMD)
                    .isolated(true)
                    .envs(env)
                    .build();
        }

        @Override
        public Path resolve(String value) {
            if (value == null || value.isBlank()) {
                return Path.of(workDir);
            }
            Path target = Path.of(value);
            return (target.isAbsolute() ? target : Path.of(workDir).resolve(target)).toAbsolutePath().normalize();
        }

        @Override
        public boolean encloses(Path path) {
            if (path == null) {
                return false;
            }
            return path.toAbsolutePath().normalize().startsWith(Path.of(workDir));
        }
    }
}
