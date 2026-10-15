package com.summit.sandbox.mxc;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.runtime.ProcessRunner;
import com.summit.core.runtime.workspace.WorkspaceBridge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Every file and shell operation runs under the same host-native isolation policy. */
public final class MxcWorkspaceBridge implements WorkspaceBridge {
    private static final int FILE_LIMIT = 16 * 1024 * 1024;
    private static final long OUTPUT_LIMIT = 4 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final MxcRuntime runtime;
    private final Path launcher;
    private final Map<String, Object> policy;
    private Charset commandCharset = StandardCharsets.UTF_8;

    MxcWorkspaceBridge(MxcRuntime runtime, Path launcher, MxcWorkspaceSpec spec) {
        this.runtime = runtime;
        this.launcher = launcher;
        this.policy = Map.of("root", runtime.nativePath(spec.workDir()), "sdkDirectory", runtime.sdkDirectory(),
                "readonlyPaths", spec.readonlyPaths().stream().map(runtime::nativePath).toList(),
                "readwritePaths", spec.readwritePaths().stream().map(runtime::nativePath).toList(),
                "environment", spec.environment(), "writable", spec.writable());
    }

    void verifyReady() throws IOException, InterruptedException {
        JsonNode result = file("probe", Map.of(), runtime.startupTimeout());
        if (!result.path("ready").isBoolean() || !result.path("ready").booleanValue()) {
            throw new IOException("MXC readiness probe did not confirm isolation");
        }
        commandCharset = resolveCommandCharset(result.path("commandCharset").asText("UTF-8"));
    }

    Charset commandCharset() { return commandCharset; }

    static Charset resolveCommandCharset(String name) {
        return Charset.forName(name.equalsIgnoreCase("cp65001") ? "UTF-8" : name);
    }

    @Override public boolean exists(Path path) {
        try {
            JsonNode result = file("exists", target(path), runtime.fileTimeout());
            if (!result.path("exists").isBoolean()) throw new IOException("Missing MXC existence result");
            return result.path("exists").booleanValue();
        } catch (IOException e) { throw new UncheckedIOException(e); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new UncheckedIOException(new IOException(e)); }
    }

    @Override public void createDirectories(Path path) throws IOException { fileUnchecked("mkdir", target(path)); }
    @Override public void deleteFile(Path path) throws IOException { fileUnchecked("delete", target(path)); }

    @Override public String readString(Path path, Charset charset) throws IOException {
        JsonNode result = fileUnchecked("read", target(path));
        if (!result.path("bytesBase64").isTextual()) throw new IOException("Missing MXC file bytes");
        try {
            byte[] bytes = Base64.getDecoder().decode(result.path("bytesBase64").textValue());
            return Objects.requireNonNull(charset).newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (IllegalArgumentException e) { throw new IOException("Invalid MXC file payload", e); }
    }

    @Override public void writeString(Path path, String content, Charset charset) throws IOException {
        ByteBuffer buffer = Objects.requireNonNull(charset).newEncoder().encode(CharBuffer.wrap(Objects.requireNonNull(content)));
        if (buffer.remaining() > FILE_LIMIT) throw new IOException("File exceeds 16 MiB limit");
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        fileUnchecked("write", Map.of("path", runtime.nativePath(path.toString()), "bytesBase64", Base64.getEncoder().encodeToString(bytes)));
    }

    @Override public CommandResult execute(String command, String workDir, Charset charset, long timeout,
                                           TimeUnit unit, long maxOutputChars) throws IOException, InterruptedException {
        if (timeout <= 0 || unit == null) throw new IllegalArgumentException("Execution timeout must be positive");
        if (maxOutputChars < 0 || maxOutputChars > OUTPUT_LIMIT) throw new IllegalArgumentException("Output limit must be between 0 and 4 Mi characters");
        Duration budget = Duration.ofMillis(Math.max(1, unit.toMillis(timeout)));
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("command", Objects.requireNonNull(command));
        if (workDir != null && !workDir.isBlank()) operation.put("workDir", runtime.nativePath(workDir));
        JsonNode result = invoke("execute", operation, budget, maxOutputChars * 4);
        byte[] bytes = outputBytes(result);
        boolean byteTruncated = result.path("truncated").booleanValue();
        CharBuffer characters = CharBuffer.allocate((int) maxOutputChars + 1);
        CoderResult decoded = Objects.requireNonNull(charset).newDecoder().decode(ByteBuffer.wrap(bytes), characters, !byteTruncated);
        if (decoded.isError()) decoded.throwException();
        String output = characters.flip().toString();
        boolean truncated = byteTruncated || decoded.isOverflow() || output.length() > maxOutputChars;
        if (output.length() > maxOutputChars) output = output.substring(0, (int) maxOutputChars);
        return new CommandResult(result.path("exitCode").intValue(), output, truncated, result.path("timedOut").booleanValue());
    }

    private Map<String, Object> target(Path path) { return Map.of("path", runtime.nativePath(Objects.requireNonNull(path).toString())); }

    private JsonNode fileUnchecked(String operation, Map<String, Object> arguments) throws IOException {
        try { return file(operation, arguments, runtime.fileTimeout()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("MXC file operation interrupted", e); }
    }

    private JsonNode file(String operation, Map<String, Object> arguments, Duration budget) throws IOException, InterruptedException {
        JsonNode result = invoke(operation, arguments, budget, 24L * 1024 * 1024);
        if (result.path("timedOut").booleanValue()) throw new IOException("MXC file operation timed out");
        byte[] bytes = outputBytes(result);
        if (result.path("exitCode").intValue() != 0) throw new IOException("MXC file operation failed: " + new String(bytes, StandardCharsets.UTF_8));
        if (result.path("truncated").booleanValue()) throw new IOException("MXC file response exceeds limit");
        return JSON.readTree(bytes);
    }

    private JsonNode invoke(String operation, Map<String, Object> arguments, Duration budget, long maxBytes)
            throws IOException, InterruptedException {
        Map<String, Object> request = new LinkedHashMap<>(policy);
        request.putAll(arguments);
        request.put("operation", operation);
        request.put("timeoutMs", budget.toMillis());
        request.put("maxBytes", maxBytes);
        String token = UUID.randomUUID().toString();
        boolean settled = false;
        try {
            CommandResult transport = ProcessRunner.run(runtime.command(launcher, token, false), null,
                    StandardCharsets.UTF_8, JSON.writeValueAsBytes(request),
                    budget.plus(runtime.startupTimeout()).plusSeconds(5).toMillis(), TimeUnit.MILLISECONDS,
                    maxBytes * 2 + 8192);
            if (transport.timedOut() || transport.truncated()) throw new IOException("MXC transport timed out or exceeded response limit");
            JsonNode result = JSON.readTree(transport.output());
            if (result != null && result.has("error")) settled = true;
            if (result == null || transport.exitCode() != 0 || result.has("error")) {
                throw new IOException("MXC launch failed: " + transport.output());
            }
            if (!result.path("exitCode").isIntegralNumber() || !result.path("timedOut").isBoolean()
                    || !result.path("truncated").isBoolean() || !result.path("outputBase64").isTextual()) {
                throw new IOException("Malformed MXC response");
            }
            return result;
        } catch (IOException | InterruptedException | RuntimeException failure) {
            if (!settled) cancel(token, failure);
            throw failure;
        }
    }

    private void cancel(String token, Exception failure) {
        boolean interrupted = Thread.interrupted();
        try {
            CommandResult cleanup = ProcessRunner.run(runtime.command(launcher, token, true), null,
                    StandardCharsets.UTF_8, 8, TimeUnit.SECONDS, 8192);
            if (cleanup.timedOut() || cleanup.exitCode() != 0) failure.addSuppressed(new IOException("MXC cancellation failed: " + cleanup.output()));
        } catch (Exception cleanupFailure) { failure.addSuppressed(cleanupFailure); }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private static byte[] outputBytes(JsonNode result) throws IOException {
        try { return Base64.getDecoder().decode(result.path("outputBase64").textValue()); }
        catch (IllegalArgumentException e) { throw new IOException("Invalid MXC output bytes", e); }
    }
}
