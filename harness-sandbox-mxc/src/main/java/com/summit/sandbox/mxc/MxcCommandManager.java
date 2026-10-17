package com.summit.sandbox.mxc;

import com.fasterxml.jackson.databind.JsonNode;
import com.summit.core.runtime.workspace.WorkspaceCommands;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CoderResult;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

final class MxcCommandManager implements WorkspaceCommands {
    private static final long OUTPUT_LIMIT = 4 * 1024 * 1024;
    private final MxcRuntime runtime;
    private final MxcTransport transport;

    MxcCommandManager(MxcRuntime runtime, MxcTransport transport) {
        this.runtime = runtime;
        this.transport = transport;
    }

    @Override
    public CommandResult execute(String command, String workDir, Charset charset, long timeout,
                                                 TimeUnit unit, long maxOutputChars) throws IOException, InterruptedException {

        if (timeout <= 0 || unit == null) throw new IllegalArgumentException("Execution timeout must be positive");

        if (maxOutputChars < 0 || maxOutputChars > OUTPUT_LIMIT)
            throw new IllegalArgumentException("Output limit must be between 0 and 4 Mi characters");

        Duration budget = Duration.ofMillis(Math.max(1, unit.toMillis(timeout)));

        Map<String, Object> operation = new LinkedHashMap<>();

        operation.put("command", Objects.requireNonNull(command));

        if (workDir != null && !workDir.isBlank()) operation.put("workDir", runtime.nativePath(workDir));

        JsonNode result = transport.invoke("execute", operation, budget, maxOutputChars * 4);

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

    private static byte[] outputBytes(JsonNode result) throws IOException {
        try {
            return Base64.getDecoder().decode(result.path("outputBase64").textValue());
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid MXC output bytes", e);
        }
    }
}
