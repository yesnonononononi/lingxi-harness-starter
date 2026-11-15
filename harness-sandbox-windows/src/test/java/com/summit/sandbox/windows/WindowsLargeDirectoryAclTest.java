package com.summit.sandbox.windows;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.WINDOWS)
class WindowsLargeDirectoryAclTest {
    private static final Path BUSINESS_REPO = Path.of("D:\\Code\\LingXi");
    private static final String BENCHMARK_RW_SID = "S-1-4-999999-1001-1";
    private static final String BENCHMARK_RO_SID = "S-1-4-999999-1001-2";

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void measureDualUserModeSwitchLatencyOnLargeBusinessRepo() {
        Assumptions.assumeTrue(Files.isDirectory(BUSINESS_REPO), "Business repo not found: " + BUSINESS_REPO);

        PointerByReference rwRef = new PointerByReference();
        PointerByReference roRef = new PointerByReference();
        assertTrue(Win32SandboxApi.Advapi32Ext.INSTANCE.ConvertStringSidToSidW(BENCHMARK_RW_SID, rwRef));
        assertTrue(Win32SandboxApi.Advapi32Ext.INSTANCE.ConvertStringSidToSidW(BENCHMARK_RO_SID, roRef));
        Pointer rwSid = rwRef.getValue();
        Pointer roSid = roRef.getValue();
        try {
            WindowsWorkspaceAcl.revokeSidPermission(BUSINESS_REPO, rwSid);
            WindowsWorkspaceAcl.revokeSidPermission(BUSINESS_REPO, roSid);

            long t0 = System.nanoTime();
            boolean rwInitChanged = WindowsWorkspaceAcl.ensureSidPermission(
                    BUSINESS_REPO, rwSid, Win32SandboxApi.FILE_MODIFY_MASK);
            long firstRwGrantMs = (System.nanoTime() - t0) / 1_000_000L;
            assertTrue(rwInitChanged, "Expected initial READ_WRITE grant to apply ACE");

            long t1 = System.nanoTime();
            boolean roInitChanged = WindowsWorkspaceAcl.ensureSidPermission(
                    BUSINESS_REPO, roSid, Win32SandboxApi.FILE_READ_EXECUTE_MASK);
            long firstRoGrantMs = (System.nanoTime() - t1) / 1_000_000L;
            assertTrue(roInitChanged, "Expected initial READ_ONLY grant to apply ACE");

            // Dual-user mode switching: READ_WRITE -> READ_ONLY -> READ_WRITE must not re-walk the tree.
            long t2 = System.nanoTime();
            boolean switchRwToRoChanged = WindowsWorkspaceAcl.ensureSidPermission(
                    BUSINESS_REPO, roSid, Win32SandboxApi.FILE_READ_EXECUTE_MASK);
            long switchRwToRoMs = (System.nanoTime() - t2) / 1_000_000L;
            assertFalse(switchRwToRoChanged, "Switching to READ_ONLY must not rewrite ACL");

            long t3 = System.nanoTime();
            boolean switchRoToRwChanged = WindowsWorkspaceAcl.ensureSidPermission(
                    BUSINESS_REPO, rwSid, Win32SandboxApi.FILE_MODIFY_MASK);
            long switchRoToRwMs = (System.nanoTime() - t3) / 1_000_000L;
            assertFalse(switchRoToRwChanged, "Switching back to READ_WRITE must not rewrite ACL");

            System.out.printf(
                    "[DualUserAcl] path=%s, firstRwGrantMs=%d, firstRoGrantMs=%d, switchRwToRoMs=%d, switchRoToRwMs=%d%n",
                    BUSINESS_REPO, firstRwGrantMs, firstRoGrantMs, switchRwToRoMs, switchRoToRwMs
            );

            assertTrue(switchRwToRoMs < 50, "RW->RO switch should be near-instant, took " + switchRwToRoMs + "ms");
            assertTrue(switchRoToRwMs < 50, "RO->RW switch should be near-instant, took " + switchRoToRwMs + "ms");
            assertTrue(firstRwGrantMs < 60_000, "Initial RW ACL grant exceeded 60s budget: " + firstRwGrantMs + "ms");
            assertTrue(firstRoGrantMs < 60_000, "Initial RO ACL grant exceeded 60s budget: " + firstRoGrantMs + "ms");
        } finally {
            WindowsWorkspaceAcl.revokeSidPermission(BUSINESS_REPO, rwSid);
            WindowsWorkspaceAcl.revokeSidPermission(BUSINESS_REPO, roSid);
            Win32SandboxApi.Kernel32Ext.INSTANCE.LocalFree(rwSid);
            Win32SandboxApi.Kernel32Ext.INSTANCE.LocalFree(roSid);
        }
    }
}
