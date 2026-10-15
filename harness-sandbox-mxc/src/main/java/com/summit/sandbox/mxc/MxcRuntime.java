package com.summit.sandbox.mxc;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Deployment-owned Node/SDK locations; no downloads, installers, or backend fallback. */
public record MxcRuntime(String distribution, String nodeExecutable, String sdkDirectory,
                         Path helperDirectory, Duration startupTimeout, Duration fileTimeout) {
    public MxcRuntime {
        nodeExecutable = linuxAbsolute(nodeExecutable);
        sdkDirectory = linuxAbsolute(sdkDirectory);
        helperDirectory = Objects.requireNonNull(helperDirectory, "helperDirectory").toAbsolutePath().normalize();
        if (distribution != null && !distribution.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid WSL distribution name");
        }
        String hostOs = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (distribution == null ? !hostOs.contains("linux") : !hostOs.contains("windows")) {
            throw new IllegalStateException("Select native Linux or Windows-to-WSL transport for this host");
        }
        if (startupTimeout == null || startupTimeout.isZero() || startupTimeout.isNegative()
                || fileTimeout == null || fileTimeout.isZero() || fileTimeout.isNegative()) {
            throw new IllegalArgumentException("Startup and file timeouts must be positive");
        }
    }

    public static MxcRuntime linux(Path node, Path sdk, Path helpers) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
            throw new IllegalStateException("Native MXC execution requires Linux; select wsl on Windows");
        }
        return new MxcRuntime(null, node.toAbsolutePath().toString(), sdk.toAbsolutePath().toString(), helpers,
                Duration.ofSeconds(30), Duration.ofSeconds(30));
    }

    public static MxcRuntime wsl(String distribution, String node, String sdk, Path helpers) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            throw new IllegalStateException("WSL transport requires Windows");
        }
        return new MxcRuntime(Objects.requireNonNull(distribution), node, sdk, helpers,
                Duration.ofSeconds(30), Duration.ofSeconds(30));
    }

    /** Maps POSIX paths or absolute host paths into the selected Linux filesystem. */
    public String linuxPath(String value) {
        Objects.requireNonNull(value, "path");
        if (value.startsWith("/") && !value.startsWith("//")) return linuxAbsolute(value);
        if (distribution == null) return linuxAbsolute(value);
        return windowsToLinux(value, distribution);
    }

    static String windowsToLinux(String value, String distribution) {
        String path = value.replace('\\', '/');
        if (path.matches("^[A-Za-z]:/.*")) {
            return linuxAbsolute("/mnt/" + Character.toLowerCase(path.charAt(0)) + path.substring(2));
        }
        for (String prefix : List.of("//wsl.localhost/", "//wsl$/")) {
            String fullPrefix = prefix + distribution + "/";
            if (path.regionMatches(true, 0, fullPrefix, 0, fullPrefix.length())) {
                return linuxAbsolute("/" + path.substring(fullPrefix.length()));
            }
        }
        throw new IllegalArgumentException("Expected an absolute drive path or a path in WSL " + distribution);
    }

    Path hostPath(String linux) {
        String normalized = linuxPath(linux);
        return distribution == null ? Path.of(normalized)
                : Path.of("\\\\wsl.localhost\\" + distribution + normalized.replace('/', '\\'));
    }

    List<String> command(Path launcher, String token, boolean cancel) {
        List<String> command = new ArrayList<>();
        if (distribution != null) command.addAll(List.of("wsl.exe", "--distribution", distribution, "--exec"));
        if (!cancel) command.addAll(List.of("/usr/bin/setsid", "--wait"));
        command.addAll(List.of("/usr/bin/env", "-i", "PATH=/usr/bin:/bin", "LC_ALL=C.UTF-8",
                "HOME=" + linuxPath(helperDirectory.toString())));
        command.addAll(List.of(nodeExecutable, linuxPath(launcher.toString()), cancel ? "cancel" : "run",
                linuxPath(helperDirectory.resolve("requests").toString()), token));
        return List.copyOf(command);
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
