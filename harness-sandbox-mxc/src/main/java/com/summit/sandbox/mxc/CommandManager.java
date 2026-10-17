package com.summit.sandbox.mxc;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.runtime.ProcessRunner;
import com.summit.core.runtime.workspace.WorkspaceBridge;
import lombok.RequiredArgsConstructor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
@RequiredArgsConstructor
public class CommandManager {
    private final MxcRuntime runtime;
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final Map<String, Object> policy;
    private final Path launcher;
    private static final long OUTPUT_LIMIT = 4 * 1024 * 1024;
    public WorkspaceBridge.CommandResult execute(String command, String workDir, Charset charset, long timeout,
                                                 TimeUnit unit, long maxOutputChars) throws IOException, InterruptedException {

        if (timeout <= 0 || unit == null) throw new IllegalArgumentException("Execution timeout must be positive");

        if (maxOutputChars < 0 || maxOutputChars > OUTPUT_LIMIT)
            throw new IllegalArgumentException("Output limit must be between 0 and 4 Mi characters");

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

        return new WorkspaceBridge.CommandResult(result.path("exitCode").intValue(), output, truncated, result.path("timedOut").booleanValue());
    }



    protected JsonNode invoke(String operation, Map<String, Object> arguments, Duration budget, long maxBytes)
            throws IOException, InterruptedException {
        Map<String, Object> request = new LinkedHashMap<>(policy);
        request.putAll(arguments);
        request.put("operation", operation);
        request.put("timeoutMs", budget.toMillis());
        request.put("maxBytes", maxBytes);
        String token = UUID.randomUUID().toString();
        boolean settled = false;
        try {
            WorkspaceBridge.CommandResult transport = ProcessRunner.run(runtime.command(launcher, token, false), null,
                    StandardCharsets.UTF_8, JSON.writeValueAsBytes(request),
                    budget.plus(runtime.startupTimeout()).plusSeconds(5).toMillis(), TimeUnit.MILLISECONDS,
                    maxBytes * 2 + 8192);
            if (transport.timedOut() || transport.truncated())
                throw new IOException("MXC transport timed out or exceeded response limit");
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

    private static byte[] outputBytes(JsonNode result) throws IOException {
        try {
            return Base64.getDecoder().decode(result.path("outputBase64").textValue());
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid MXC output bytes", e);
        }
    }


    private void cancel(String token, Exception failure) {
        boolean interrupted = Thread.interrupted();
        try {
            WorkspaceBridge.CommandResult cleanup = ProcessRunner.run(runtime.command(launcher, token, true), null,
                    StandardCharsets.UTF_8, 8, TimeUnit.SECONDS, 8192);
            if (cleanup.timedOut() || cleanup.exitCode() != 0)
                failure.addSuppressed(new IOException("MXC cancellation failed: " + cleanup.output()));
        } catch (Exception cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

}
