package com.summit.sandbox.windows;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Resolves the virtual capability SIDs and integrity SIDs used by the restricted-token sandbox
 * without creating local user accounts.
 */
final class WindowsSandboxAccount {
    static final String READ_ONLY_PROFILE = "ro";
    static final String READ_WRITE_PROFILE = "rw";

    static final String READ_ONLY_SID = "S-1-5-99-1001-1";
    static final String READ_WRITE_SID = "S-1-5-99-1001-2";

    private WindowsSandboxAccount() {
    }

    static String profileFor(SandboxMode mode) {
        return mode == SandboxMode.READ_WRITE ? READ_WRITE_PROFILE : READ_ONLY_PROFILE;
    }

    static String sidStringFor(SandboxMode mode) {
        return mode == SandboxMode.READ_WRITE ? READ_WRITE_SID : READ_ONLY_SID;
    }

    static Memory sandboxSid(SandboxMode mode) {
        return sidFromString(sidStringFor(mode));
    }

    /**
     * Derives a deterministic per-workspace virtual capability SID so granting access
     * to one project directory never grants access to another project directory.
     */
    static String workspaceSidString(Path workspaceRoot, SandboxMode mode) {
        String normalized = workspaceRoot.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buf = ByteBuffer.wrap(digest);
            String sub1 = Integer.toUnsignedString(buf.getInt(0));
            String sub2 = Integer.toUnsignedString(buf.getInt(4));
            int rid = mode == SandboxMode.READ_WRITE ? 2 : 1;
            return "S-1-5-99-" + sub1 + "-" + sub2 + "-" + rid;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    static Memory workspaceSid(Path workspaceRoot, SandboxMode mode) {
        return sidFromString(workspaceSidString(workspaceRoot, mode));
    }

    static Memory sidFromString(String stringSid) {
        PointerByReference sidRef = new PointerByReference();
        if (!Win32SandboxApi.Advapi32Ext.INSTANCE.ConvertStringSidToSidW(stringSid, sidRef)
                || sidRef.getValue() == null) {
            int err = Win32SandboxApi.Kernel32Ext.INSTANCE.GetLastError();
            throw new IllegalStateException("ConvertStringSidToSidW failed for " + stringSid + ": win32=" + err);
        }
        Pointer nativeSid = sidRef.getValue();
        try {
            return copySidToMemory(nativeSid);
        } finally {
            Win32SandboxApi.Kernel32Ext.INSTANCE.LocalFree(nativeSid);
        }
    }

    static Memory copySidToMemory(Pointer sidPtr) {
        int length = Win32SandboxApi.Advapi32Ext.INSTANCE.GetLengthSid(sidPtr);
        if (length <= 0) {
            throw new IllegalStateException("Invalid SID length: " + length);
        }
        byte[] bytes = sidPtr.getByteArray(0, length);
        Memory mem = new Memory(length);
        mem.write(0, bytes, 0, length);
        return mem;
    }
}
