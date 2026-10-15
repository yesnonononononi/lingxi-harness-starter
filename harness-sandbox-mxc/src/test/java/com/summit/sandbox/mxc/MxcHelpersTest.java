package com.summit.sandbox.mxc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MxcHelpersTest {
    @TempDir Path directory;

    @Test void reusesMatchingResourcesAndRejectsTamperingWithoutOverwritingIt() throws Exception {
        Path launcher = MxcHelpers.deploy(directory);
        assertTrue(Files.readString(launcher).contains("bubblewrap"));
        assertEquals(launcher, MxcHelpers.deploy(directory));
        Files.writeString(launcher, "modified");
        assertThrows(IOException.class, () -> MxcHelpers.deploy(directory));
        assertEquals("modified", Files.readString(launcher));
    }
}
