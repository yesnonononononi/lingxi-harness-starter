package com.summit.sandbox.mxc;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.runtime.CommandHandler;
import com.summit.core.runtime.CancellableProcessRunner;
import com.summit.core.runtime.ProcessRunner;
import com.summit.core.runtime.workspace.WorkspaceCommands.CommandResult;
import com.summit.core.runtime.workspace.WorkspaceStdioLaunch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sends JSON requests through the MXC launcher and validates its response envelope.
 * File operations, shell commands and persistent stdio sessions share the same workspace policy.
 */
final class MxcTransport {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final MxcRuntime runtime;
    private final Path launcher;
    private final Map<String, Object> policy;

    MxcTransport(MxcRuntime runtime, Path launcher, Map<String, Object> policy) {
        this.runtime = runtime;
        this.launcher = launcher;
        this.policy = Map.copyOf(policy);
    }

    /**
     * Prepares policy-bound launch metadata; the caller owns starting and closing the session.
     */
    WorkspaceStdioLaunch prepareStdio(List<String> command, Map<String, String> environment) throws IOException {
        return MxcStdioSession.prepare(runtime, launcher, policy, command, environment);
    }


    /** Starts a framed command stream; the host deadline also allows launcher startup and cleanup. */
    CommandHandler start(String operation, Map<String, Object> arguments, Duration budget, Charset charset, int maxChars) throws IOException {
        Map<String, Object> request = buildRequest(operation, arguments, budget, (long) maxChars * 4);
        request.put("streamOutput", true);
        String token = UUID.randomUUID().toString();
        MxcOutputCollector collector = new MxcOutputCollector(charset, maxChars);
        try {
            CommandHandler host = new CancellableProcessRunner().start(runtime.command(launcher, token, false), null,
                    StandardCharsets.UTF_8, JSON.writeValueAsBytes(request),
                    budget.plus(runtime.startupTimeout()).plusSeconds(5).toMillis(), TimeUnit.MILLISECONDS, 0, collector::accept);
            return new MxcCommandHandle(host, collector, () -> cancel(token));
        } catch (IOException | RuntimeException failure) {
            cancel(token, failure);
            throw failure;
        }
    }
    /**
     * Waits for one launcher response; a command's nonzero exit code remains result data.
     * The budget and byte limit apply inside MXC, with separate allowances for launcher overhead.
     */
    JsonNode invoke(String operation, Map<String, Object> arguments, Duration budget, long maxBytes)
            throws IOException, InterruptedException {
        Map<String, Object> request = buildRequest(operation, arguments, budget, maxBytes);

        // Cleanup uses this token rather than relying on the outer launcher's process handle.
        String token = UUID.randomUUID().toString();

        AtomicReference<Boolean> settled = new AtomicReference<>(false);
        try {
            CommandResult transport = ProcessRunner.run(
                    runtime.command(launcher, token, false),
                    null,
                    StandardCharsets.UTF_8,
                    JSON.writeValueAsBytes(request),
                    budget.plus(runtime.startupTimeout()).plusSeconds(5).toMillis(),
                    TimeUnit.MILLISECONDS,
                    maxBytes * 2 + 8192
            );

            return handleResult(transport, settled);
        } catch (IOException | InterruptedException | RuntimeException failure) {
            if (!settled.getAndSet(true)) cancel(token, failure);
            throw failure;
        }
    }


    private JsonNode handleResult(CommandResult transport, AtomicReference<Boolean> settled) throws IOException{

        if (transport.timedOut() || transport.truncated())
            throw new IOException("MXC transport timed out or exceeded response limit");

        JsonNode result = JSON.readTree(transport.output());

        // A reported launcher error has already passed through the launcher's own cleanup.
        if (result != null && result.has("error")) settled.set(true);

        if (result == null || transport.exitCode() != 0 || result.has("error")) {
            throw new IOException("MXC launch failed: " + transport.output());
        }

        if (!result.path("exitCode").isIntegralNumber() || !result.path("timedOut").isBoolean()
                || !result.path("truncated").isBoolean() || !result.path("outputBase64").isTextual()) {
            throw new IOException("Malformed MXC response");
        }

        return result;
    }


    Map<String, Object> buildRequest(String operation, Map<String, Object> arguments, Duration budget, long maxBytes) {
        Map<String, Object> request = new LinkedHashMap<>(policy);
        request.putAll(arguments);
        if ("execute".equals(operation)) {
            @SuppressWarnings("unchecked") Map<String, String> configured = (Map<String, String>) request.getOrDefault("environment", Map.of());
            request.put("environment", MxcHostEnvironment.resolve(configured, runtime));
        }
        request.put("operation", operation);
        request.put("timeoutMs", budget.toMillis());
        request.put("maxBytes", maxBytes);
        return request;
    }


    /** Cleanup failures remain visible to explicit stop callers. */
    void cancel(String token) throws IOException, InterruptedException {
        CommandResult cleanup = ProcessRunner.run(runtime.command(launcher, token, true), null,
                StandardCharsets.UTF_8, 8, TimeUnit.SECONDS, 8192);
        if (cleanup.timedOut() || cleanup.exitCode() != 0) throw new IOException("MXC cancellation failed: " + cleanup.output());
    }

    private void cancel(String token, Exception failure) {
        boolean interrupted = Thread.interrupted();
        try { cancel(token); }
        catch (Exception cleanup) { failure.addSuppressed(cleanup); }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
