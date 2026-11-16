package com.summit.sandbox.windows;

import com.summit.core.runtime.RuntimeEnvironment;
import com.summit.core.runtime.workspace.OsType;
import com.summit.core.runtime.workspace.ShellType;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.runtime.workspace.WorkspaceBridge;
import com.summit.core.workspace.WorkspaceAccessMode;
import com.summit.core.workspace.WorkspaceOptions;
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
 * {@link WorkspaceProvider} implementation backed by the Windows Restricted Token sandbox
 * and virtual capability SIDs ({@value WindowsSandboxAccount#READ_ONLY_SID} and
 * {@value WindowsSandboxAccount#READ_WRITE_SID}).
 */
public final class WindowsWorkspaceProvider implements WorkspaceProvider {
    public static final String TYPE = "windows-sandbox";

    public WindowsWorkspaceProvider() {
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Workspace resolve(WorkspaceSpec spec) {
        return resolve(spec, null);
    }

    @Override
    public Workspace resolve(WorkspaceSpec spec, WorkspaceOptions options) {
        if (!(spec instanceof WindowsWorkspaceSpec(
                String workDir, String scope, Map<String, String> environment, boolean writable
        ))) {
            throw new IllegalArgumentException("WindowsWorkspaceProvider requires a WindowsWorkspaceSpec");
        }
        String effectiveWorkDir = options != null && options.getWorkDir() != null && !options.getWorkDir().isBlank()
                ? options.getWorkDir() : workDir;
        Path root = Path.of(effectiveWorkDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Workspace directory does not exist: " + root);
        }

        boolean effectiveWritable = options != null && options.getAccessMode() != null
                ? options.getAccessMode() == WorkspaceAccessMode.READ_WRITE
                : writable;
        Map<String, String> effectiveEnv = new LinkedHashMap<>(environment);
        if (options != null && options.getEnvironment() != null) {
            effectiveEnv.putAll(options.getEnvironment());
        }

        SandboxMode mode = effectiveWritable ? SandboxMode.READ_WRITE : SandboxMode.READ_ONLY;
        WindowsWorkspaceAcl.ensureWorkspacePermission(root, mode);

        Map<String, String> frozenEnv = Map.copyOf(effectiveEnv);
        WindowsWorkspaceBridge bridge = new WindowsWorkspaceBridge(root, mode, frozenEnv);
        String rootStr = root.toString();
        String id = WorkspaceRef.derived(TYPE, scope + "|" + rootStr + "|" + mode.name()).id();
        return new ResolvedWorkspace(id, rootStr, mode, bridge, frozenEnv);
    }

    private record ResolvedWorkspace(
            String id,
            String workDir,
            SandboxMode mode,
            WorkspaceBridge bridge,
            Map<String, String> environment
    ) implements Workspace {
        @Override
        public RuntimeEnvironment runtimeEnvironment() {
            Map<String, String> env = new LinkedHashMap<>(environment);
            String sysRoot = Objects.requireNonNullElse(System.getenv("SystemRoot"), "C:\\Windows");
            env.putIfAbsent("PATH", sysRoot + "\\System32;" + sysRoot);
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
