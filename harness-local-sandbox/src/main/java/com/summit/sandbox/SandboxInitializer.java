package com.summit.sandbox;

import com.summit.core.runtime.RuntimeEnvironment;
import com.summit.core.runtime.workspace.OsType;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.runtime.workspace.WorkspaceBridge;
import com.summit.core.runtime.ProcessRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.ArrayList;
import java.util.List;

/** Checks caller-supplied local SRT resources independently of the workspace shell. */
public class SandboxInitializer {

    private final CommandTimeoutConfig timeout;
    private final Workspace workspace;
    private final ProcessRunner processRunner;

    public SandboxInitializer(CommandTimeoutConfig timeout, Workspace workspace) {
        this(timeout, workspace, new ProcessRunner());
    }

    SandboxInitializer(CommandTimeoutConfig timeout, Workspace workspace, ProcessRunner processRunner) {
        this.timeout = Objects.requireNonNull(timeout, "Timeout configuration is null");
        this.workspace = Objects.requireNonNull(workspace, "Workspace is null");
        this.processRunner = Objects.requireNonNull(processRunner, "Command runner is null");
    }

    /**
     * Verifies Node, CLI loading and an actual sandboxed command. Does not install or repair
     * machine-wide Windows components. Git Bash is not required for these checks.
     *
     * @param srtCli the package's JavaScript CLI entry point, not a npm shell shim
     * @param node the bundled Node executable
     */
    public void init(Path srtCli, Path node) throws IOException {
        init(srtCli, node, null);
    }

    /** Checks the actual policy file selected by a provider, without consulting SRT's default settings. */
    public void init(Path srtCli, Path node, Path settingsFile) throws IOException {
        OsType os = osType();
        if (settingsFile != null) requireFile(settingsFile, "SRT settings");
        ensureComponents(srtCli, node);
        ensureSandbox(srtCli, node, os, settingsFile);
    }

    /**
     * Explicitly provisions Windows sandbox components (may prompt for UAC), then probes them.
     * The application decides when to offer this operation after a failed check.
     */
    public void installWindows(Path srtCli, Path node) throws IOException {
        installWindows(srtCli, node, timeout);
    }

    /**
     * Same as {@link #installWindows(Path, Path)}, with a budget of its own.
     *
     * <p>Installation is a self-elevating child process waiting on a modal UAC prompt, and SRT
     * budgets 120s for it — usually wider than a probe budget. Reusing the probe budget here lets
     * the runner kill the process before SRT is done, and a machine that is already provisioned
     * then still looks like a failed install. The install command <em>and</em> the probe after it
     * use this budget: a first probe right after provisioning is slower than a warm one.</p>
     */
    public void installWindows(Path srtCli, Path node, CommandTimeoutConfig installTimeout) throws IOException {
        Objects.requireNonNull(installTimeout, "Install timeout is null");
        OsType os = osType();
        if (os != OsType.WINDOWS) {
            throw new IllegalStateException("Windows sandbox installation requires Windows");
        }
        ensureComponents(srtCli, node);
        requireSuccess(
                runCommand(installTimeout, node.toAbsolutePath().toString(),
                srtCli.toAbsolutePath().toString(), "windows-install"),
                "Windows sandbox installation failed"
        );
        ensureSandbox(srtCli, node, os, null, installTimeout);
    }

    private OsType osType() {
        RuntimeEnvironment environment = Objects.requireNonNull(
                workspace.runtimeEnvironment(), "Runtime environment is null");
        return Objects.requireNonNull(environment.osType(), "OS type is null");
    }

    /**
     * ```shell
     * node --version
     * node srt --version
     * ```
     */
    private void ensureComponents(Path srtCli, Path node) throws IOException {
        requireFile(srtCli, "SRT JavaScript CLI");
        requireFile(node, "Node executable");
        requireSuccess(runCommand(node.toAbsolutePath().toString(), "--version"), "Node check failed");
        requireSuccess(runCommand(node.toAbsolutePath().toString(),
                srtCli.toAbsolutePath().toString(), "--version"), "SRT CLI loading failed");
    }

    /**
     * ```shell
     * node srt --settings <file> -c whoami
     * ```
     * @param srtCli the package's JavaScript CLI entry point, not an npm shell shim
     * @param node the bundled Node executable
     * @param os the operating system
     * @param settingsFile the directory containing the `settings.json` file
     */
    private void ensureSandbox(Path srtCli, Path node, OsType os, Path settingsFile) throws IOException {
        ensureSandbox(srtCli, node, os, settingsFile, timeout);
    }

    private void ensureSandbox(Path srtCli, Path node, OsType os, Path settingsFile,
                               CommandTimeoutConfig budget) throws IOException {
        List<String> command = new ArrayList<>(List.of(node.toAbsolutePath().toString(), srtCli.toAbsolutePath().toString()));
        if (settingsFile != null) command.addAll(List.of("--settings", settingsFile.toAbsolutePath().toString()));
        command.addAll(List.of("-c", "whoami"));
        WorkspaceBridge.CommandResult result = runCommand(budget, command.toArray(String[]::new));
        String message = "SRT sandbox probe failed";
        if (os == OsType.WINDOWS) {
            message += "; inspect the diagnostic output before explicitly calling installWindows if installation is needed";
        }
        requireSuccess(result, message);
    }

    private WorkspaceBridge.CommandResult runCommand(String... command) throws IOException {
        return runCommand(timeout, command);
    }

    private WorkspaceBridge.CommandResult runCommand(CommandTimeoutConfig budget, String... command) throws IOException {
        // Argument boundaries preserve spaces and shell metacharacters in bundled paths.
        return processRunner.run(command, budget.timeout(), budget.timeUnit());
    }

    private static void requireFile(Path path, String label) {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IllegalStateException(label + " is missing or is not a regular file: " + path);
        }
    }

    private static void requireSuccess(WorkspaceBridge.CommandResult result, String message) {
        if (!result.isSuccess() || result.timedOut() || result.truncated()) {
            throw new IllegalStateException(message + " (exit code " + result.exitCode() + "): "
                    + result.output());
        }
    }
}
