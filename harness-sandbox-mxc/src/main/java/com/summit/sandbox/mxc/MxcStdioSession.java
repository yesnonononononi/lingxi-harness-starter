package com.summit.sandbox.mxc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.runtime.ProcessRunner;
import com.summit.core.runtime.workspace.WorkspaceCommands.CommandResult;
import com.summit.core.runtime.workspace.WorkspaceStdioLaunch;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns a single persistent process, not the workspace or any external runtime installation. */
final class MxcStdioSession implements Closeable {
    private final MxcRuntime runtime;
    private final Path launcher;
    private final String token = UUID.randomUUID().toString();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Path requestFile;

    private MxcStdioSession(MxcRuntime runtime, Path launcher) throws IOException {
        this.runtime = runtime;
        this.launcher = launcher;
        Path controls = runtime.helperDirectory().resolve("requests");
        Files.createDirectories(controls);
        requestFile = controls.resolve(token + ".request.json");
    }

    static WorkspaceStdioLaunch prepare(MxcRuntime runtime, Path launcher, Map<String, Object> policy,
                                         List<String> command, Map<String, String> environment) throws IOException {
        if (command == null || command.isEmpty() || command.stream().anyMatch(v -> v == null || v.indexOf('\0') >= 0)) {
            throw new IllegalArgumentException("Invalid MCP stdio argv");
        }
        MxcStdioSession session = new MxcStdioSession(runtime, launcher);
        Map<String, Object> request = new LinkedHashMap<>(policy);
        @SuppressWarnings("unchecked") Map<String, String> configured = (Map<String, String>) policy.get("environment");
        Map<String, String> env = new LinkedHashMap<>(configured);
        env.putAll(environment);
        request.put("environment", env);
        request.put("argv", List.copyOf(command));
        request.put("operation", "stdio");
        request.put("timeoutMs", 0);
        byte[] bytes = new ObjectMapper().writeValueAsBytes(request);
        if (runtime.windows()) Files.write(session.requestFile, bytes, StandardOpenOption.CREATE_NEW);
        else Files.write(Files.createFile(session.requestFile, PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rw-------"))), bytes);
        List<String> wrapped = runtime.command(launcher, session.token, "stdio");
        // The outer process is trusted deployment code; do not inherit Node injection settings.
        return new WorkspaceStdioLaunch(wrapped, Map.of("NODE_OPTIONS", "", "NODE_PATH", ""), session);
    }

    @Override public void close() throws IOException {
        if (!closed.compareAndSet(false, true) || !Files.exists(requestFile)) return;
        boolean interrupted = Thread.interrupted();
        try {
            CommandResult result = ProcessRunner.run(runtime.command(launcher, token, true), null,
                    StandardCharsets.UTF_8, 8, TimeUnit.SECONDS, 8192);
            if (result.timedOut() || result.exitCode() != 0) throw new IOException("MXC stdio cancellation failed: " + result.output());
            Path started = requestFile.resolveSibling(token + ".started");
            // No registered supervisor can have read the recipe yet. Removing it also prevents a delayed start.
            if (!Files.exists(started)) Files.deleteIfExists(requestFile);
            long deadline = System.nanoTime() + runtime.startupTimeout().plusSeconds(8).toNanos();
            while ((Files.exists(requestFile) || Files.exists(started)) && System.nanoTime() < deadline) Thread.sleep(25);
            if (Files.exists(requestFile) || Files.exists(started)) throw new IOException("MXC stdio supervisor did not acknowledge cancellation");
            Files.deleteIfExists(requestFile.resolveSibling(token + ".cancel"));
        } catch (InterruptedException failure) {
            interrupted = true;
            throw new IOException("Interrupted while closing MXC stdio", failure);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
