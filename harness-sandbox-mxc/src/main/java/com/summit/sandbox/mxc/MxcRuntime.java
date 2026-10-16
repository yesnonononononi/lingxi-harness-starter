package com.summit.sandbox.mxc;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Host-native execution only: Windows BaseContainer or Linux Bubblewrap, without fallback. */
public record MxcRuntime(String nodeExecutable,
                         String sdkDirectory,
                         Path helperDirectory,
                         Duration startupTimeout,
                         Duration fileTimeout
) {
    public MxcRuntime {
        String os = hostOs();
        if (!os.contains("windows") && !os.contains("linux")) throw new IllegalStateException("MXC requires Windows or Linux");
        nodeExecutable = nativeAbsolute(nodeExecutable);
        sdkDirectory = nativeAbsolute(sdkDirectory);
        helperDirectory = Objects.requireNonNull(helperDirectory).toAbsolutePath().normalize();
        if (startupTimeout == null || startupTimeout.isNegative() || startupTimeout.isZero()
                || fileTimeout == null || fileTimeout.isNegative() || fileTimeout.isZero()) {
            throw new IllegalArgumentException("Startup and file timeouts must be positive");
        }
    }

    public static MxcRuntime nativeHost(Path node, Path sdk, Path helpers) {
        return new MxcRuntime(node.toString(), sdk.toString(), helpers, Duration.ofSeconds(30), Duration.ofSeconds(30));
    }
    public boolean windows() { return windowsHost(); }
    public String nativePath(String value) { return nativeAbsolute(value); }
    Path hostPath(String value) { return Path.of(nativePath(value)); }

    /** Lower-cased host OS name, re-read on every call so tests can override {@code os.name}. */
    private static String hostOs() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT); }

    /** True on the native Windows backend; the single source of truth for OS detection in this adapter. */
    static boolean windowsHost() { return hostOs().contains("windows"); }

    List<String> command(Path launcher, String token, boolean cancel) {
        return command(launcher, token, cancel ? "cancel" : "run");
    }

    List<String> command(Path launcher, String token, String mode) {
        if (!List.of("run", "cancel", "stdio").contains(mode)) throw new IllegalArgumentException("Invalid launch mode");
        boolean cancel = mode.equals("cancel");
        List<String> command = new ArrayList<>();
        if (!windows()) {
            if (!cancel) command.addAll(List.of("/usr/bin/setsid", "--wait"));
            command.addAll(List.of("/usr/bin/env", "-i", "PATH=/usr/bin:/bin", "LC_ALL=C.UTF-8", "HOME=" + helperDirectory));
        }
        command.addAll(List.of(nodeExecutable, launcher.toString(), mode,
                helperDirectory.resolve("requests").toString(), token));
        return List.copyOf(command);
    }

    static String nativeAbsolute(String value) {
        if (value == null || value.indexOf('\0') >= 0 || value.contains("\n") || value.contains("\r")) {
            throw new IllegalArgumentException("Expected an absolute native path");
        }
        if (windowsHost()) {
            if (!value.matches("^[A-Za-z]:[\\\\/].*")) throw new IllegalArgumentException("Expected an absolute Windows drive path");
            return Path.of(value).normalize().toString();
        }
        return linuxAbsolute(value);
    }

    static String linuxAbsolute(String value) {
        if (value == null || !value.startsWith("/") || value.startsWith("//") || value.indexOf('\0') >= 0
                || value.indexOf('\\') >= 0 || value.contains("\n") || value.contains("\r")) {
            throw new IllegalArgumentException("Expected an absolute POSIX path");
        }
        List<String> parts = new ArrayList<>();
        for (String part : value.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) { if (!parts.isEmpty()) parts.removeLast(); }
            else parts.add(part);
        }
        return "/" + String.join("/", parts);
    }
}
