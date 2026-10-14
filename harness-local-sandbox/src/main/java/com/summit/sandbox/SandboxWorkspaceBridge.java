package com.summit.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.summit.core.runtime.RuntimeEnvironment;
import com.summit.core.runtime.workspace.OsType;
import com.summit.core.runtime.workspace.WorkspaceBridge;
import lombok.Getter;
import com.summit.core.runtime.ProcessRunner;
import com.summit.sandbox.internal.HelperLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Routes raw scripts and file operations through the caller-selected SRT policy.
 */
@Getter
public final class SandboxWorkspaceBridge implements WorkspaceBridge {
    private final Path srtCli;
    private final Path nodeExe;
    private final ObjectMapper objectMapper;
    private final CommandTimeoutConfig timeout;
    private final RuntimeEnvironment runtimeEnvironment;
    private final Path settingsFile;
    private final Path requestDirectory;
    private final Path applicationDirectory;
    private static final String EXISTS_SCRIPT = "exists.mjs";
    private static final String READ_FILE_SCRIPT = "readFile.mjs";
    private static final String WRITE_FILE_SCRIPT = "writeFile.mjs";
    private static final String DELETE_FILE_SCRIPT = "deleteFile.mjs";
    private static final String EXECUTE_COMMAND_SCRIPT = "executeCommand.mjs";
    private static final String MKDIR_SCRIPT = "mkdir.mjs";
    private static final String SRT_CLI_SCRIPT = "srtCli.mjs";

    /**
     * Deployment owns RX access to installed helpers and read-only request-directory access.
     */
    public SandboxWorkspaceBridge(Path srtCli, Path nodeExe, ObjectMapper objectMapper,
                                  CommandTimeoutConfig timeout, RuntimeEnvironment runtimeEnvironment,
                                  Path settingsFile, Path requestDirectory, Path applicationDirectory) {
        this.srtCli = Objects.requireNonNull(srtCli, "srtCli must not be null").toAbsolutePath().normalize();
        this.nodeExe = Objects.requireNonNull(nodeExe, "nodeExe must not be null").toAbsolutePath().normalize();

        if (!Files.isRegularFile(srtCli)) throw new IllegalArgumentException("srtCli must be a regular file");

        if (!Files.isRegularFile(nodeExe)) throw new IllegalArgumentException("nodeExe must be a regular file");

        if (settingsFile != null && !Files.isRegularFile(settingsFile)) {
            throw new IllegalArgumentException("settingsFile must be a regular file");
        }
        if (requestDirectory != null && !Files.isDirectory(requestDirectory)) {
            throw new IllegalArgumentException("requestDirectory must be an existing directory");
        }
        this.applicationDirectory =
                Objects.requireNonNull(applicationDirectory, "applicationDirectory must not be null").toAbsolutePath().normalize();

        if (!Files.isDirectory(applicationDirectory))
            throw new IllegalArgumentException("applicationDirectory must be an existing directory");

        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        this.runtimeEnvironment = Objects.requireNonNull(runtimeEnvironment, "runtimeEnvironment must not be null");

        Objects.requireNonNull(runtimeEnvironment.shellType(), "shellType must not be null");
        OsType os = Objects.requireNonNull(runtimeEnvironment.osType(), "workspace osType must not be null");

        if (os == OsType.UNKNOWN) throw new IllegalArgumentException("workspace osType must be known");

        this.settingsFile = settingsFile == null ? null : settingsFile.toAbsolutePath().normalize();
        this.requestDirectory = requestDirectory == null ? null : requestDirectory.toAbsolutePath().normalize();

    }

    @Override
    public boolean exists(Path path) {
        try {
            CommandResult result = runWithSrt(EXISTS_SCRIPT, pathRequest(path));
            requireSuccess(result);
            JsonNode exists = response(result).get("exists");
            if (exists == null || !exists.isBoolean()) throw new IOException("Invalid sandbox exists response");
            return exists.booleanValue();
        } catch (IOException e) {
            // The interface has no checked exception; backend failure is not a missing file.
            throw new UncheckedIOException("Sandbox existence check failed", e);
        }
    }

    @Override
    public void createDirectories(Path path) throws IOException {
        requireSuccess(runWithSrt(MKDIR_SCRIPT, pathRequest(path)));
    }

    @Override
    public void deleteFile(Path path) throws IOException {
        requireSuccess(runWithSrt(DELETE_FILE_SCRIPT, pathRequest(path)));
    }

    @Override
    public String readString(Path path, Charset charset) throws IOException {
        Objects.requireNonNull(charset, "charset must not be null");
        CommandResult result = runWithSrt(READ_FILE_SCRIPT, pathRequest(path));
        requireSuccess(result);
        JsonNode encoded = response(result).get("bytesBase64");
        if (encoded == null || !encoded.isTextual()) throw new IOException("Invalid sandbox read response");
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded.textValue());
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid sandbox file Base64", e);
        }
        return charset.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }

    @Override
    public void writeString(Path path, String content, Charset charset) throws IOException {
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(charset, "charset must not be null");
        ByteBuffer encoded = charset.newEncoder().encode(CharBuffer.wrap(content));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        Map<String, Object> request = Map.of(
                "path", pathRequest(path).get("path"),
                "bytesBase64", Base64.getEncoder().encodeToString(bytes)
        );
        requireSuccess(runWithSrt(WRITE_FILE_SCRIPT, request));
    }

    @Override
    public CommandResult execute(String command, String workDir, Charset charset, long timeout,
                                 TimeUnit timeUnit, long maxOutputChars) throws IOException, InterruptedException {
        Objects.requireNonNull(command, "command must not be null");
        return runHelper(EXECUTE_COMMAND_SCRIPT, Map.of("argv", runtimeEnvironment.shellType().buildCommand(command)),
                workDir, charset, timeout, timeUnit, maxOutputChars);
    }

    private CommandResult runWithSrt(String scriptName, Map<String, Object> request) throws IOException {
        try {
            return runHelper(scriptName, request, null, StandardCharsets.UTF_8,
                    timeout.timeout(), timeout.timeUnit(), Long.MAX_VALUE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Sandbox file operation interrupted", e);
        }
    }

    private CommandResult runHelper(String scriptName, Map<String, Object> request, String workDir,
                                    Charset charset, long timeout, TimeUnit unit, long maxOutputChars)
            throws IOException, InterruptedException {
        try (HelperRequest transport = prepareRequest(request)) {
            return ProcessRunner.run(helperInvocation(scriptName, transport), workDir, charset,
                    timeout, unit, maxOutputChars);
        }
    }

    void verifyReady(Path root, List<Path> protectedDirectories, List<Path> denyRead, List<Path> denyWrite,
                     boolean workspaceWritable)
            throws IOException {

        List<Path> readTargets = new ArrayList<>();

        for (Path denied : denyRead) {

            if (Files.isRegularFile(denied)) {
                readTargets.add(denied);

            } else if (Files.isDirectory(denied)) {
                try (Stream<Path> descendants = Files.walk(denied)) {
                    descendants.filter(Files::isRegularFile).findFirst().ifPresent(readTargets::add);
                }
            }
        }
        CommandResult result = runWithSrt("probe.mjs",
                Map.of(
                        "root", root.toString(),
                        "workspaceWritable", workspaceWritable,
                        "argv", runtimeEnvironment.shellType().buildCommand("echo sandbox-ready"),

                        "protectedDirectories",
                        protectedDirectories.stream().map(Path::toString).toList(),

                        "denyRead",
                        probeTargets(readTargets),

                        "denyWrite",
                        probeTargets(denyWrite))
        );

        requireSuccess(result);

        if (!response(result).path("ready").asBoolean(false))
            throw new IOException("Invalid sandbox readiness response");

    }

    private static List<Map<String, Object>> probeTargets(List<Path> paths) {
        return paths.stream().filter(Files::exists).map(path -> Map.<String, Object>of(
                "path", path.toString(), "directory", Files.isDirectory(path))).toList();
    }

    private List<String> helperInvocation(String scriptName, HelperRequest transport) throws IOException {

        // SRT's Windows CLI starts helpers with its default CMD shell. Workspace
        // commands travel separately as JSON argv and are never flattened here.
        HelperLoader.resolve(applicationDirectory, "request.mjs");

        Path helper = HelperLoader.resolve(applicationDirectory, scriptName);

        String requestPath = Base64.getEncoder().encodeToString(transport.request().toString().getBytes(StandardCharsets.UTF_8));

        String helperCommand;

        if (runtimeEnvironment.osType() == OsType.WINDOWS) {
            helperCommand = resolveHelperCommandIfWin(helper, requestPath);
        } else {
            helperCommand = resolveHelperCommandElse(helper, requestPath);
        }

        // Java's Windows process launcher may consume embedded quotes. Only
        // Base64 crosses that argv boundary; the Node launcher reconstructs -c.
        List<String> invocation = new ArrayList<>(
                List.of(nodeExe.toString(),
                        HelperLoader.resolve(applicationDirectory, SRT_CLI_SCRIPT).toString(),
                        srtCli.toString(),
                        Base64.getEncoder().encodeToString(helperCommand.getBytes(StandardCharsets.UTF_8)))
        );

        if (settingsFile != null) invocation.add(settingsFile.toString());

        return invocation;
    }


    private String resolveHelperCommandIfWin(Path helper, String requestPath) {
        // Only Base64 is parsed by SRT's CMD bootstrap. Legal path characters
        // such as %, $, &, and apostrophes remain literals inside PowerShell.
        String script = "& '" + nodeExe.toString().replace("'", "''")
                + "' '" + helper.toString().replace("'", "''") + "' '" + requestPath + "'; exit $LASTEXITCODE";
        return "powershell.exe -NoProfile -NonInteractive -EncodedCommand "
                + Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
    }

    private String resolveHelperCommandElse(Path helper, String requestPath) {
        return "exec '" + nodeExe.toString().replace("'", "'\"'\"'")
                + "' '" + helper.toString().replace("'", "'\"'\"'") + "' '" + requestPath + "'";
    }


    private HelperRequest prepareRequest(Map<String, Object> request) throws IOException {

        Path directory = (requestDirectory == null ? Files.createTempDirectory("lingxi-sandbox-request-")
                : Files.createTempDirectory(requestDirectory, "lingxi-sandbox-request-")).toAbsolutePath().normalize();

        HelperRequest transport = new HelperRequest(directory, directory.resolve("request.json"));

        try {
            objectMapper.writeValue(transport.request().toFile(), request);
            return transport;
        } catch (IOException | RuntimeException e) {
            try {
                transport.close();
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    private record HelperRequest(Path directory, Path request) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            IOException failure = null;
            for (Path path : List.of(request, directory)) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    if (failure == null) failure = e;
                    else failure.addSuppressed(e);
                }
            }
            if (failure != null) throw failure;
        }
    }

    private void requireSuccess(CommandResult commandResult) throws IOException {
        if (commandResult.timedOut() || commandResult.truncated() || !commandResult.isSuccess()) {
            throw new IOException("Sandbox operation failed (exit=" + commandResult.exitCode()
                    + ", timedOut=" + commandResult.timedOut() + "): " + commandResult.output());
        }
    }

    private Map<String, Object> pathRequest(Path path) {
        return Map.of("path", Objects.requireNonNull(path, "path must not be null").toAbsolutePath().normalize().toString());
    }

    private JsonNode response(CommandResult result) throws IOException {
        JsonNode response = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readTree(result.output());
        if (response == null || !response.isObject()) throw new IOException("Invalid sandbox helper response");
        return response;
    }

}
