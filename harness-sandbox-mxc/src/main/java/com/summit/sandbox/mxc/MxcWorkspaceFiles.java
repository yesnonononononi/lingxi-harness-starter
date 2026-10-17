package com.summit.sandbox.mxc;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.runtime.workspace.WorkspaceFiles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

final class MxcWorkspaceFiles implements WorkspaceFiles {
    private static final int FILE_LIMIT = 16 * 1024 * 1024;
    private static final long FILE_RESPONSE_LIMIT = 3L * FILE_LIMIT / 2;
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final MxcRuntime runtime;
    private final MxcTransport transport;

    MxcWorkspaceFiles(MxcRuntime runtime, MxcTransport transport) {
        this.runtime = runtime;
        this.transport = transport;
    }

    Charset probe() throws IOException, InterruptedException {
        JsonNode result = file("probe", Map.of(), runtime.startupTimeout());
        if (!result.path("ready").isBoolean() || !result.path("ready").booleanValue()) {
            throw new IOException("MXC readiness probe did not confirm isolation");
        }
        return resolveCommandCharset(result.path("commandCharset").asText("UTF-8"));
    }

    static Charset resolveCommandCharset(String name) {
        return Charset.forName(name.equalsIgnoreCase("cp65001") ? "UTF-8" : name);
    }

    @Override
    public boolean exists(Path path) {
        try {
            JsonNode result = file("exists", target(path), runtime.fileTimeout());
            if (!result.path("exists").isBoolean()) throw new IOException("Missing MXC existence result");
            return result.path("exists").booleanValue();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new IOException(e));
        }
    }

    @Override
    public void createDirectories(Path path) throws IOException {
        fileUnchecked("mkdir", target(path));
    }

    @Override
    public void deleteFile(Path path) throws IOException {
        fileUnchecked("delete", target(path));
    }

    @Override
    public String readString(Path path, Charset charset) throws IOException {
        JsonNode result = fileUnchecked("read", target(path));
        if (!result.path("bytesBase64").isTextual()) throw new IOException("Missing MXC file bytes");
        try {
            byte[] bytes = Base64.getDecoder().decode(result.path("bytesBase64").textValue());
            return Objects.requireNonNull(charset).newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid MXC file payload", e);
        }
    }

    @Override
    public void writeString(Path path, String content, Charset charset) throws IOException {
        ByteBuffer buffer = Objects.requireNonNull(charset).newEncoder().encode(CharBuffer.wrap(Objects.requireNonNull(content)));
        if (buffer.remaining() > FILE_LIMIT) throw new IOException("File exceeds 16 MiB limit");
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        fileUnchecked("write", Map.of("path", runtime.nativePath(path.toString()), "bytesBase64", Base64.getEncoder().encodeToString(bytes)));
    }



    private JsonNode file(String operation, Map<String, Object> arguments, Duration budget) throws IOException, InterruptedException {
        JsonNode result = transport.invoke(operation, arguments, budget, FILE_RESPONSE_LIMIT);
        if (result.path("timedOut").booleanValue()) throw new IOException("MXC file operation timed out");
        byte[] bytes = outputBytes(result);
        if (result.path("exitCode").intValue() != 0)
            throw new IOException("MXC file operation failed: " + new String(bytes, StandardCharsets.UTF_8));
        if (result.path("truncated").booleanValue()) throw new IOException("MXC file response exceeds limit");
        return JSON.readTree(bytes);
    }

    private static byte[] outputBytes(JsonNode result) throws IOException {
        try {
            return Base64.getDecoder().decode(result.path("outputBase64").textValue());
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid MXC output bytes", e);
        }
    }


    private JsonNode fileUnchecked(String operation, Map<String, Object> arguments) throws IOException {
        try {
            return file(operation, arguments, runtime.fileTimeout());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("MXC file operation interrupted", e);
        }
    }
    private Map<String, Object> target(Path path) {
        return Map.of("path", runtime.nativePath(Objects.requireNonNull(path).toString()));
    }

}
