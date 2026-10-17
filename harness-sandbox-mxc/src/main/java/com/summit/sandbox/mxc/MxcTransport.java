package com.summit.sandbox.mxc;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.runtime.ProcessRunner;
import com.summit.core.runtime.workspace.WorkspaceCommands.CommandResult;
import com.summit.core.runtime.workspace.WorkspaceStdioLaunch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** A single policy must govern file requests, shell requests and persistent stdio sessions. */
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

    WorkspaceStdioLaunch prepareStdio(List<String> command, Map<String, String> environment) throws IOException {
        return MxcStdioSession.prepare(runtime, launcher, policy, command, environment);
    }

    JsonNode invoke(String operation, Map<String, Object> arguments, Duration budget, long maxBytes)
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

    private void cancel(String token, Exception failure) {
        boolean interrupted = Thread.interrupted();
        try {
            CommandResult cleanup = ProcessRunner.run(runtime.command(launcher, token, true), null,
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
