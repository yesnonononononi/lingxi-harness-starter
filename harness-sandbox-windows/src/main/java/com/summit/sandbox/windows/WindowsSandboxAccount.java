package com.summit.sandbox.windows;

import com.sun.jna.Memory;
import com.sun.jna.ptr.IntByReference;

import java.util.Optional;

/**
 * Resolves the dedicated read-only and read-write Windows sandbox accounts via {@code LookupAccountNameW}.
 */
final class WindowsSandboxAccount {
    static final String READ_ONLY_USERNAME = "LingXiSandBoxRo";
    static final String READ_WRITE_USERNAME = "LingXiSandBoxRw";

    private WindowsSandboxAccount() {
    }

    static String usernameFor(SandboxMode mode) {
        return mode == SandboxMode.READ_WRITE ? READ_WRITE_USERNAME : READ_ONLY_USERNAME;
    }

    static Optional<Memory> lookupSid(String accountName) {
        IntByReference sidSize = new IntByReference(0);
        IntByReference domainSize = new IntByReference(0);
        IntByReference sidUse = new IntByReference();

        Win32SandboxApi.Advapi32Ext.INSTANCE.LookupAccountNameW(
                null, accountName, null, sidSize, null, domainSize, sidUse);
        int len = sidSize.getValue();
        if (len <= 0) {
            return Optional.empty();
        }

        byte[] sidBytes = new byte[len];
        char[] domainBuf = new char[Math.max(1, domainSize.getValue())];
        boolean ok = Win32SandboxApi.Advapi32Ext.INSTANCE.LookupAccountNameW(
                null, accountName, sidBytes, sidSize, domainBuf, domainSize, sidUse);
        if (!ok) {
            return Optional.empty();
        }

        Memory mem = new Memory(len);
        mem.write(0, sidBytes, 0, len);
        return Optional.of(mem);
    }
}
