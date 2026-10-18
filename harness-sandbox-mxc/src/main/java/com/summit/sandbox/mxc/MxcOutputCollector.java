package com.summit.sandbox.mxc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.runtime.workspace.WorkspaceCommands.CommandResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.util.Base64;

/** Bounded launcher frames retain command bytes, never expose protocol JSON as command output. */
final class MxcOutputCollector {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_FRAME = 16384;
    private final Charset charset;
    private final int maxChars;
    private final long maxBytes;
    private final StringBuilder pending = new StringBuilder();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private JsonNode terminal;

    MxcOutputCollector(Charset charset, int maxChars) {
        this.charset = charset;
        this.maxChars = maxChars;
        this.maxBytes = (long) maxChars * 4;
    }

    synchronized void accept(String text) throws IOException {
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (value == '\n') {
                if (!pending.isEmpty()) parseFrame(pending.toString());
                pending.setLength(0);
            } else {
                if (pending.length() >= MAX_FRAME) throw new IOException("MXC output frame exceeds limit");
                pending.append(value);
            }
        }
    }

    private void parseFrame(String frame) throws IOException {
        if (terminal != null) throw new IOException("MXC output after terminal response");
        JsonNode node = JSON.readTree(frame);
        if (node == null || !node.isObject()) throw new IOException("Malformed MXC output frame");
        if (node.path("type").asText().equals("output")) {
            if (!node.path("data").isTextual()) throw new IOException("Malformed MXC output bytes");
            byte[] chunk;
            try { chunk = Base64.getDecoder().decode(node.path("data").textValue()); }
            catch (IllegalArgumentException failure) { throw new IOException("Invalid MXC output bytes", failure); }
            if (chunk.length > maxBytes - bytes.size()) throw new IOException("MXC output exceeds requested byte limit");
            bytes.writeBytes(chunk);
        } else if (node.path("type").asText().equals("result") || node.has("error")) terminal = node;
        else throw new IOException("Unknown MXC output frame");
    }

    synchronized boolean settled() { return terminal != null && terminal.has("error"); }

    synchronized String snapshot() {
        try { return decode(false).text(); }
        catch (IOException failure) { throw new IllegalStateException("Cannot decode MXC command output", failure); }
    }

    synchronized CommandResult finish(CommandResult host) throws IOException {
        if (!pending.isEmpty()) { parseFrame(pending.toString()); pending.setLength(0); }
        if (terminal != null && terminal.has("error")) throw new IOException("MXC launch failed: " + terminal.path("error").asText());
        if (host.timedOut()) throw new IOException("MXC transport timed out");
        if (host.exitCode() != 0 || terminal == null) throw new IOException("MXC launcher exited without a terminal response");
        if (!terminal.path("exitCode").isIntegralNumber() || !terminal.path("timedOut").isBoolean()
                || !terminal.path("truncated").isBoolean()) throw new IOException("Malformed MXC terminal response");
        boolean byteTruncated = terminal.path("truncated").booleanValue();
        Decoded decoded = decode(!byteTruncated);
        return new CommandResult(terminal.path("exitCode").intValue(), decoded.text(),
                byteTruncated || decoded.truncated(), terminal.path("timedOut").booleanValue());
    }

    private Decoded decode(boolean complete) throws IOException {
        CharsetDecoder decoder = charset.newDecoder();
        CharBuffer characters = CharBuffer.allocate(maxChars + 1);
        CoderResult status = decoder.decode(ByteBuffer.wrap(bytes.toByteArray()), characters, complete);
        if (status.isError()) status.throwException();
        if (complete && status.isUnderflow()) {
            CoderResult flush = decoder.flush(characters);
            if (flush.isError()) flush.throwException();
        }
        String text = characters.flip().toString();
        boolean truncated = status.isOverflow() || text.length() > maxChars;
        return new Decoded(text.length() > maxChars ? text.substring(0, maxChars) : text, truncated);
    }
    private record Decoded(String text, boolean truncated) { }
}