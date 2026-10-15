package com.summit.sandbox.mxc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/** Extracts versioned, content-checked classpath helpers into a deployment-owned directory. */
final class MxcHelpers {
    private MxcHelpers() { }

    static synchronized Path deploy(Path directory) throws IOException {
        byte[] launcher = resource(MxcRuntime.windowsHost() ? "windows-launcher.mjs" : "launcher.mjs");
        byte[] operations = resource("operations.mjs");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(launcher);
            digest.update(operations);
            Path version = directory.resolve(HexFormat.of().formatHex(digest.digest()));
            Files.createDirectories(version);
            install(version.resolve("launcher.mjs"), launcher);
            install(version.resolve("operations.mjs"), operations);
            return version.resolve("launcher.mjs");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream input = MxcHelpers.class.getResourceAsStream("/mxc/" + name)) {
            if (input == null) throw new IOException("Missing MXC helper: " + name);
            return input.readAllBytes();
        }
    }

    private static void install(Path file, byte[] bytes) throws IOException {
        try { Files.write(file, bytes, StandardOpenOption.CREATE_NEW); }
        catch (FileAlreadyExistsException ignored) { /* Concurrent extraction verifies the same content below. */ }
        if (Files.isSymbolicLink(file) || !Arrays.equals(bytes, Files.readAllBytes(file))) {
            throw new IOException("MXC helper content mismatch: " + file);
        }
    }
}
