package com.summit.sandbox.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class HelperLoaderTest {
    private static final List<String> HELPERS = List.of("mkdir.mjs", "deleteFile.mjs", "writeFile.mjs",
            "executeCommand.mjs", "srtCli.mjs", "readFile.mjs", "exists.mjs", "request.mjs", "probe.mjs");

    @TempDir
    Path temporary;

    @Test
    void classpathHelpersBecomeAbsoluteFilesWithIdenticalBytes() throws Exception {
        for (String helper : HELPERS) {
            Path extracted = HelperLoader.resolve(temporary, helper);
            assertTrue(extracted.isAbsolute());
            assertTrue(Files.isRegularFile(extracted));
            assertEquals(helper, extracted.getFileName().toString());
            assertEquals(temporary.resolve(HelperLoader.RELATIVE_DIRECTORY).resolve(helper), extracted);
            assertArrayEquals(resourceBytes(helper), Files.readAllBytes(extracted));
        }
    }

    @Test
    void repeatedResolutionReusesTheFileWithoutRecopying() throws Exception {
        Path first = HelperLoader.resolve(temporary, "mkdir.mjs");
        FileTime marker = FileTime.fromMillis(1_600_000_000_000L);
        Files.setLastModifiedTime(first, marker);

        assertEquals(first, HelperLoader.resolve(temporary, "mkdir.mjs"));
        assertEquals(marker, Files.getLastModifiedTime(first));
    }

    @Test
    void aDeletedExtractedFileIsRecoveredFromTheResource() throws Exception {
        Path extracted = HelperLoader.resolve(temporary, "writeFile.mjs");
        Files.delete(extracted);

        assertEquals(extracted, HelperLoader.resolve(temporary, "writeFile.mjs"));
        assertArrayEquals(resourceBytes("writeFile.mjs"), Files.readAllBytes(extracted));
    }

    @Test
    void staleInstalledHelperIsReplacedOnFirstUse() throws Exception {
        Path installation = Files.createDirectories(temporary.resolve("application with spaces"));
        Path directory = Files.createDirectories(installation.resolve(HelperLoader.RELATIVE_DIRECTORY));
        Path stale = directory.resolve("mkdir.mjs");
        Files.writeString(stale, "old application version");
        assertEquals(stale, HelperLoader.resolve(installation, "mkdir.mjs"));
        assertArrayEquals(resourceBytes("mkdir.mjs"), Files.readAllBytes(stale));
    }

    @Test
    void illegalNamesAndMissingResourcesFailExplicitly() {
        for (String name : List.of("", "../mkdir.mjs", "folder/mkdir.mjs", "folder\\mkdir.mjs", "mkdir.js")) {
            assertThrows(IllegalArgumentException.class, () -> HelperLoader.resolve(temporary, name));
        }
        assertThrows(IllegalArgumentException.class, () -> HelperLoader.resolve(temporary, null));
        FileNotFoundException failure = assertThrows(FileNotFoundException.class,
                () -> HelperLoader.resolve(temporary, "absent.mjs"));
        assertTrue(failure.getMessage().contains("absent.mjs"));
    }

    @Test
    void resourcesInsideAJarAreExtractedWithoutConvertingTheJarUrlToAFilePath() throws Exception {
        Path jar = bundledJar();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
            assertEquals("jar", loader.getResource("mkdir.mjs").getProtocol());
            Class<?> helperLoader = loader.loadClass("com.summit.sandbox.internal.HelperLoader");
            Method resolve = helperLoader.getMethod("resolve", Path.class, String.class);
            for (String helper : HELPERS) {
                Path extracted = (Path) resolve.invoke(null, temporary, helper);
                assertTrue(extracted.isAbsolute());
                assertTrue(Files.isRegularFile(extracted));
                assertArrayEquals(resourceBytes(helper), Files.readAllBytes(extracted));
                assertEquals(temporary.resolve(HelperLoader.RELATIVE_DIRECTORY).resolve(helper), extracted);
            }
        }
    }

    @Test
    void concurrentFirstResolutionPublishesOneCompleteHelper() throws Exception {
        Path jar = bundledJar();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null);
             ExecutorService executor = Executors.newFixedThreadPool(6)) {
            Method resolve = loader.loadClass("com.summit.sandbox.internal.HelperLoader").getMethod("resolve", Path.class, String.class);
            List<Future<Path>> calls = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                calls.add(executor.submit(() -> (Path) resolve.invoke(null, temporary, "mkdir.mjs")));
            }
            Path first = calls.getFirst().get(5, TimeUnit.SECONDS);
            byte[] expected = resourceBytes("mkdir.mjs");
            for (Future<Path> call : calls) {
                Path extracted = call.get(5, TimeUnit.SECONDS);
                assertEquals(first, extracted);
                assertArrayEquals(expected, Files.readAllBytes(extracted));
            }
        }
    }

    private Path bundledJar() throws IOException {
        Path jar = temporary.resolve("helpers bundle.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            addResource(output, "com/summit/sandbox/internal/HelperLoader.class");
            for (String helper : HELPERS) {
                addResource(output, helper);
            }
        }
        return jar;
    }

    private static void addResource(JarOutputStream output, String name) throws IOException {
        output.putNextEntry(new JarEntry(name));
        try (InputStream input = HelperLoader.class.getResourceAsStream("/" + name)) {
            assertNotNull(input, "test fixture resource missing: " + name);
            input.transferTo(output);
        }
        output.closeEntry();
    }

    private static byte[] resourceBytes(String name) throws IOException {
        try (InputStream input = HelperLoader.class.getResourceAsStream("/" + name)) {
            assertNotNull(input, "resource missing: " + name);
            return input.readAllBytes();
        }
    }
}
