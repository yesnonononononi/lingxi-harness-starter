package com.summit.core.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards the framework's production sources against business concepts leaking back in.
 *
 * <p>Plan/Task and every other product concept belong to the application that consumes the
 * framework. This test is a plain text scan on purpose: it needs no new dependency, it runs on
 * every build, and it fails the moment a migrated type is copied back into a framework module.</p>
 *
 * <p>It deliberately does <b>not</b> forbid the bare word {@code plan}: the framework legitimately
 * talks about a "planning" loop boundary, and comment/README wording must not cause false alarms.
 * Only the concrete identifiers of the migrated business code are rejected.</p>
 */
class ArchitectureBoundaryTest {

    /** Marker that identifies the repository root unambiguously. */
    private static final String ROOT_MARKER = "<module>harness-core</module>";

    /** One {@code <module>name</module>} declaration; the name may span lines. */
    private static final Pattern MODULE_DECLARATION = Pattern.compile("<module>\\s*([^<]*?)\\s*</module>");

    /** XML comments are stripped first so a commented-out declaration is not mistaken for a real one. */
    private static final Pattern XML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    /** Substrings that must never appear in framework production code, with the reason behind each. */
    private static final List<String> FORBIDDEN_MARKERS = List.of(
            "com.summit.core.plan",
            "com.summit.core.internalUtils.plan",
            "com.summit.runtime.coreTools.plan",
            "internalUtils.plan",
            "coreTools.plan",
            "PlanUpdateEvent",
            "PLAN_UPDATED",
            "onPlanUpdate",
            "execute_command",
            "read_file",
            "edit_file",
            "web_search",
            "FileEditEvent",
            "FILE_EDIT",
            "FileRecordManager",
            // the framework must never depend on a consuming application
            "com.summit.dp.");

    /** Sanity floor: a broken root detection would silently scan nothing, so require real files. */
    private static final int MINIMUM_SCANNED_FILES = 50;

    /**
     * Scans the production sources of every module the root pom declares.
     *
     * <p>The module list is derived from the root pom rather than hard-coded, so adding a module to
     * the build covers it here automatically instead of silently skipping the boundary scan.</p>
     */
    @Test
    void frameworkProductionSourcesCarryNoBusinessConcepts() throws IOException {
        Path root = repositoryRoot();
        List<String> violations = new ArrayList<>();
        int scannedFiles = 0;

        for (String module : declaredModules(root.resolve("pom.xml"))) {
            Path sources = root.resolve(module).resolve("src").resolve("main").resolve("java");
            if (!Files.isDirectory(sources)) {
                // modules without production sources are fine; their absence is not a boundary breach
                continue;
            }
            try (Stream<Path> walk = Files.walk(sources)) {
                List<Path> javaFiles = walk
                        .filter(path -> path.toString().endsWith(".java"))
                        .toList();
                scannedFiles += javaFiles.size();
                for (Path file : javaFiles) {
                    collectViolations(root, file, violations);
                }
            }
        }

        assertTrue(scannedFiles >= MINIMUM_SCANNED_FILES,
                "the boundary scan only saw " + scannedFiles + " production files under " + root
                        + ": the repository root was probably resolved incorrectly");

        assertTrue(violations.isEmpty(),
                () -> "business concepts leaked into framework production code:\n  - "
                        + String.join("\n  - ", violations));
    }

    /**
     * Guards against empty or misplaced modules: every module the root pom declares must actually
     * ship files under {@code src/}.
     *
     * <p>The boundary scan above skips modules without production sources, which is deliberate for
     * the scan itself but would let a module that carries no code slip through unnoticed. This
     * assertion closes that gap.</p>
     *
     * <p>It deliberately checks for regular <em>files</em> rather than directory existence: git
     * does not track empty directories, so a directory-based check passes on a machine that happens
     * to hold an empty {@code src/main/java} and fails on a fresh clone or CI. A module that ships
     * nothing but a {@code pom.xml} is exactly the shape this guard exists to reject.</p>
     */
    @Test
    void everyDeclaredModuleShipsFiles() throws IOException {
        Path root = repositoryRoot();
        List<String> modules = declaredModules(root.resolve("pom.xml"));
        // No emptiness assertion here: repositoryRoot() only returns a pom containing ROOT_MARKER,
        // which is itself a <module> declaration, so the list cannot be empty.

        List<String> missing = new ArrayList<>();
        for (String module : modules) {
            if (!shipsFiles(root.resolve(module).resolve("src"))) {
                missing.add(module);
            }
        }

        assertTrue(missing.isEmpty(),
                () -> "declared modules that ship no files under src/ (empty or misplaced modules):\n  - "
                        + String.join("\n  - ", missing));
    }

    /** True when {@code directory} exists and holds at least one regular file. */
    private static boolean shipsFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk.anyMatch(Files::isRegularFile);
        }
    }

    /** Extracts the {@code <module>} entries of a pom with a plain text scan (no XML dependency). */
    private static List<String> declaredModules(Path pom) throws IOException {
        String content = XML_COMMENT.matcher(Files.readString(pom, StandardCharsets.UTF_8)).replaceAll("");
        List<String> modules = new ArrayList<>();
        Matcher matcher = MODULE_DECLARATION.matcher(content);
        while (matcher.find()) {
            String module = matcher.group(1).trim();
            if (!module.isEmpty()) {
                modules.add(module);
            }
        }
        return modules;
    }

    private static void collectViolations(Path root, Path file, List<String> violations) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            for (String marker : FORBIDDEN_MARKERS) {
                if (line.contains(marker)) {
                    violations.add(root.relativize(file) + ":" + (i + 1) + " contains '" + marker + "'");
                }
            }
        }
    }

    /**
     * Walks up from the current working directory until the repository root is found.
     *
     * <p>The test must never silently pass because it scanned the wrong directory: when the root
     * cannot be located the test fails outright.</p>
     */
    private static Path repositoryRoot() {
        Path candidate = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && candidate != null; depth++) {
            Path pom = candidate.resolve("pom.xml");
            if (Files.isRegularFile(pom) && containsRootMarker(pom)) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        return fail("could not locate the repository root (a pom.xml declaring " + ROOT_MARKER
                + ") walking up from " + Paths.get("").toAbsolutePath());
    }

    private static boolean containsRootMarker(Path pom) {
        try {
            return Files.readString(pom, StandardCharsets.UTF_8).contains(ROOT_MARKER);
        } catch (IOException e) {
            return false;
        }
    }
}
