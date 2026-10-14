package com.summit.sandbox.internal;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.List;

/** Extracts bundled helpers to real files independently of the application's working directory. */
public final class HelperLoader {
    private static final Set<Path> verifiedHelpers = new HashSet<>();
    public static final Path RELATIVE_DIRECTORY = Path.of("resources", "sandbox-helpers");
    private static final List<String> HELPERS = List.of("exists.mjs", "mkdir.mjs", "deleteFile.mjs", "readFile.mjs",
            "writeFile.mjs", "executeCommand.mjs", "request.mjs", "srtCli.mjs", "probe.mjs");

    private HelperLoader() {
    }

    public static Path extractAll(Path applicationDirectory) throws IOException {
        for (String helper : HELPERS) resolve(applicationDirectory, helper);
        return applicationDirectory.toAbsolutePath().normalize().resolve(RELATIVE_DIRECTORY);
    }

    /**
     * Resolves a classpath-root helper, including resources inside a JAR.
     * Resolves beneath an explicit application installation root, independently
     * of the current working directory. Files persist across application restarts.
     * First use verifies the installed bytes against this JAR; later calls reuse
     * the file without reading its content again. Deployment owns sandbox RX access.
     */
    public static synchronized Path resolve(Path applicationDirectory, String scriptName) throws IOException {
        if (scriptName == null || !scriptName.matches("[A-Za-z0-9_-]+\\.mjs")) {
            throw new IllegalArgumentException("Helper name must be a plain .mjs filename");
        }

        Path root = Objects.requireNonNull(applicationDirectory, "applicationDirectory must not be null")
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IOException("Application installation directory does not exist: " + root);
        Path extractionDirectory = Files.createDirectories(root.resolve(RELATIVE_DIRECTORY));
        if (!extractionDirectory.toRealPath().startsWith(root.toRealPath())) {
            throw new IOException("Helper directory resolves outside the application installation");
        }
        Path target = extractionDirectory.resolve(scriptName);
        if (verifiedHelpers.contains(target) && Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return target;

        try (InputStream resource = HelperLoader.class.getResourceAsStream("/" + scriptName)) {
            if (resource == null) {
                throw new FileNotFoundException("Bundled helper resource not found: " + scriptName);
            }
            byte[] expected = resource.readAllBytes();
            if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    && Arrays.equals(expected, Files.readAllBytes(target))) {
                verifiedHelpers.add(target);
                return target;
            }

            publish(target, expected);
            verifiedHelpers.add(target);
            return target;
        }
    }

    /** Publishes complete bytes atomically; identical existing files need no write permission. */
    public static void publish(Path target, byte[] content) throws IOException {
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                && Arrays.equals(content, Files.readAllBytes(target))) return;
        Path pending = Files.createTempFile(target.getParent(), target.getFileName() + "-", ".part");
        try {
            Files.write(pending, content);
            try {
                Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(pending);
        }
    }
}
