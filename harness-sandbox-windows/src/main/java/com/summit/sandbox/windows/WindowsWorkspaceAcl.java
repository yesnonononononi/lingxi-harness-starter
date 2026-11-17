package com.summit.sandbox.windows;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Idempotently applies {@code READ_ONLY} (RX) or {@code READ_WRITE} (Modify)
 * ACEs via controlled tree walking and non-propagating {@code SetFileSecurityW},
 * skipping heavy build/dependency cache directories.
 */
final class WindowsWorkspaceAcl {
    private static final int ERROR_FILE_NOT_FOUND = 2;
    private static final int ERROR_PATH_NOT_FOUND = 3;
    private static final int ERROR_ACCESS_DENIED = 5;
    private static final int ERROR_SHARING_VIOLATION = 32;

    private static final Set<String> DEFAULT_SKIP_DIRS = Set.of(
            "node_modules", "target", "dist", "build", ".gradle", ".mvn",
            "__pycache__", ".venv", "venv", ".next", ".nuxt", "out", ".run"
    );

    private WindowsWorkspaceAcl() {
    }

    static boolean ensureWorkspacePermission(Path workspaceRoot, SandboxMode mode) {
        Path normalized = workspaceRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException("Workspace directory does not exist: " + normalized);
        }
        Memory sidMemory = WindowsSandboxAccount.workspaceSid(normalized, mode);
        int desiredMask = mode.writable()
                ? Win32SandboxApi.FILE_MODIFY_MASK
                : Win32SandboxApi.FILE_READ_EXECUTE_MASK;
        return ensureSidPermission(normalized, sidMemory, desiredMask);
    }

    static boolean ensureProfilePermission(Path profileDir, SandboxMode mode) {
        Path normalized = profileDir.toAbsolutePath().normalize();
        Memory sidMemory = WindowsSandboxAccount.sandboxSid(mode);
        return ensureSidPermission(normalized, sidMemory, Win32SandboxApi.FILE_MODIFY_MASK);
    }

    static boolean ensureSidPermission(Path workspaceRoot, Pointer sidPointer, int desiredMask) {
        Path normalized = workspaceRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException("Workspace directory does not exist: " + normalized);
        }
        if (hasRootMatchingAce(normalized, sidPointer, desiredMask)) {
            return false;
        }
        applyControlledTree(normalized, sidPointer, desiredMask, Win32SandboxApi.SET_ACCESS);
        return true;
    }

    static boolean revokeSidPermission(Path workspaceRoot, Pointer sidPointer) {
        Path normalized = workspaceRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            return false;
        }
        if (!hasRootAnyAce(normalized, sidPointer)) {
            return false;
        }
        applyControlledTree(normalized, sidPointer, 0, Win32SandboxApi.REVOKE_ACCESS);
        return true;
    }

    private static void applyControlledTree(
            Path root,
            Pointer sidPointer,
            int desiredMask,
            int accessMode
    ) {
        List<Path> children = collectTargetPaths(root);
        int threads = Math.clamp(Runtime.getRuntime().availableProcessors(), 4, 8);
        if (!children.isEmpty()) {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                int chunkSize = Math.max(1, (children.size() + threads - 1) / threads);
                List<Callable<Void>> tasks = new ArrayList<>();
                for (int start = 0; start < children.size(); start += chunkSize) {
                    int from = start;
                    int to = Math.min(children.size(), start + chunkSize);
                    tasks.add(() -> {
                        for (int i = from; i < to; i++) {
                            Path target = children.get(i);
                            boolean isDir = Files.isDirectory(target);
                            applySinglePathNonPropagating(target, sidPointer, desiredMask, accessMode, isDir, true);
                        }
                        return null;
                    });
                }
                List<Future<Void>> futures = pool.invokeAll(tasks);
                for (Future<Void> future : futures) {
                    future.get();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while applying workspace ACL", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new IllegalStateException("Failed while applying workspace ACL", cause);
            } finally {
                pool.shutdownNow();
            }
        }
        // Apply root directory last so an interrupted walk never leaves root marked complete.
        applySinglePathNonPropagating(root, sidPointer, desiredMask, accessMode, true, false);
    }

    private static List<Path> collectTargetPaths(Path root) {
        List<Path> targets = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(root)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (shouldSkipDirectory(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    targets.add(dir);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    targets.add(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to traverse workspace directory: " + root, e);
        }
        return targets;
    }

    static boolean shouldSkipDirectory(Path dir) {
        Path fileName = dir.getFileName();
        if (fileName == null) {
            return false;
        }
        String name = fileName.toString().toLowerCase(Locale.ROOT);
        return DEFAULT_SKIP_DIRS.contains(name);
    }

    private static boolean hasRootMatchingAce(Path root, Pointer sidPointer, int desiredMask) {
        String path = root.toString();
        PointerByReference daclRef = new PointerByReference();
        PointerByReference sdRef = new PointerByReference();
        int getStatus = Win32SandboxApi.Advapi32Ext.INSTANCE.GetNamedSecurityInfoW(
                path,
                Win32SandboxApi.SE_FILE_OBJECT,
                Win32SandboxApi.DACL_SECURITY_INFORMATION,
                null,
                null,
                daclRef,
                null,
                sdRef
        );
        if (getStatus != 0) {
            throw new IllegalStateException("GetNamedSecurityInfoW failed: win32=" + getStatus + ", path=" + path);
        }
        Pointer sd = sdRef.getValue();
        try {
            Pointer existingDacl = daclRef.getValue();
            return existingDacl != null && hasExactInheritableAllowAce(existingDacl, sidPointer, desiredMask);
        } finally {
            if (sd != null) {
                Win32SandboxApi.Kernel32Ext.INSTANCE.LocalFree(sd);
            }
        }
    }

    private static boolean hasRootAnyAce(Path root, Pointer sidPointer) {
        String path = root.toString();
        PointerByReference daclRef = new PointerByReference();
        PointerByReference sdRef = new PointerByReference();
        int getStatus = Win32SandboxApi.Advapi32Ext.INSTANCE.GetNamedSecurityInfoW(
                path,
                Win32SandboxApi.SE_FILE_OBJECT,
                Win32SandboxApi.DACL_SECURITY_INFORMATION,
                null,
                null,
                daclRef,
                null,
                sdRef
        );
        if (getStatus != 0) {
            throw new IllegalStateException("GetNamedSecurityInfoW failed: win32=" + getStatus + ", path=" + path);
        }
        Pointer sd = sdRef.getValue();
        try {
            Pointer existingDacl = daclRef.getValue();
            return existingDacl != null && hasAnyAceForSid(existingDacl, sidPointer);
        } finally {
            if (sd != null) {
                Win32SandboxApi.Kernel32Ext.INSTANCE.LocalFree(sd);
            }
        }
    }

    private static void applySinglePathNonPropagating(
            Path target,
            Pointer sidPointer,
            int desiredMask,
            int accessMode,
            boolean isDirectory,
            boolean skipOnChildInaccessible
    ) {
        String path = target.toString();
        PointerByReference daclRef = new PointerByReference();
        PointerByReference sdRef = new PointerByReference();
        int getStatus = Win32SandboxApi.Advapi32Ext.INSTANCE.GetNamedSecurityInfoW(
                path,
                Win32SandboxApi.SE_FILE_OBJECT,
                Win32SandboxApi.DACL_SECURITY_INFORMATION,
                null,
                null,
                daclRef,
                null,
                sdRef
        );
        if (getStatus != 0) {
            if (skipOnChildInaccessible && isIgnorableChildAclError(getStatus)) {
                return;
            }
            throw new IllegalStateException("GetNamedSecurityInfoW failed: win32=" + getStatus + ", path=" + path);
        }

        Pointer existingSd = sdRef.getValue();
        Pointer newAcl = null;
        try {
            Pointer existingDacl = daclRef.getValue();
            if (accessMode == Win32SandboxApi.SET_ACCESS
                    && existingDacl != null
                    && (isDirectory
                        ? hasExactInheritableAllowAce(existingDacl, sidPointer, desiredMask)
                        : hasExactFileAllowAce(existingDacl, sidPointer, desiredMask))) {
                return;
            }
            if (accessMode == Win32SandboxApi.REVOKE_ACCESS
                    && (existingDacl == null || !hasAnyAceForSid(existingDacl, sidPointer))) {
                return;
            }

            Win32SandboxApi.ExplicitAccess entry = new Win32SandboxApi.ExplicitAccess();
            entry.grfAccessPermissions = desiredMask;
            entry.grfAccessMode = accessMode;
            entry.grfInheritance = isDirectory
                    ? (Win32SandboxApi.OBJECT_INHERIT_ACE | Win32SandboxApi.CONTAINER_INHERIT_ACE)
                    : Win32SandboxApi.NO_INHERITANCE;
            entry.Trustee.pMultipleTrustee = null;
            entry.Trustee.MultipleTrusteeOperation = Win32SandboxApi.NO_MULTIPLE_TRUSTEE;
            entry.Trustee.TrusteeForm = Win32SandboxApi.TRUSTEE_IS_SID;
            entry.Trustee.TrusteeType = Win32SandboxApi.TRUSTEE_IS_UNKNOWN;
            entry.Trustee.ptstrName = sidPointer;

            Win32SandboxApi.ExplicitAccess[] entries = new Win32SandboxApi.ExplicitAccess[]{entry};
            PointerByReference newAclRef = new PointerByReference();
            int aclStatus = Win32SandboxApi.Advapi32Ext.INSTANCE.SetEntriesInAclW(1, entries, existingDacl, newAclRef);
            if (aclStatus != 0 || newAclRef.getValue() == null) {
                throw new IllegalStateException("SetEntriesInAclW failed: win32=" + aclStatus);
            }
            newAcl = newAclRef.getValue();

            Memory sdMem = new Memory(Win32SandboxApi.SECURITY_DESCRIPTOR_MIN_LENGTH);
            if (!Win32SandboxApi.Advapi32Ext.INSTANCE.InitializeSecurityDescriptor(
                    sdMem, Win32SandboxApi.SECURITY_DESCRIPTOR_REVISION)) {
                int err = Win32SandboxApi.Kernel32Ext.INSTANCE.GetLastError();
                throw new IllegalStateException("InitializeSecurityDescriptor failed: win32=" + err);
            }
            if (!Win32SandboxApi.Advapi32Ext.INSTANCE.SetSecurityDescriptorDacl(sdMem, true, newAcl, false)) {
                int err = Win32SandboxApi.Kernel32Ext.INSTANCE.GetLastError();
                throw new IllegalStateException("SetSecurityDescriptorDacl failed: win32=" + err);
            }
            // SetFileSecurityW updates only this object without invoking tree-wide inheritance propagation.
            if (!Win32SandboxApi.Advapi32Ext.INSTANCE.SetFileSecurityW(
                    path, Win32SandboxApi.DACL_SECURITY_INFORMATION, sdMem)) {
                int err = Win32SandboxApi.Kernel32Ext.INSTANCE.GetLastError();
                if (skipOnChildInaccessible && isIgnorableChildAclError(err)) {
                    return;
                }
                throw new IllegalStateException("SetFileSecurityW failed: win32=" + err + ", path=" + path);
            }
        } finally {
            if (newAcl != null) {
                Win32SandboxApi.Kernel32Ext.INSTANCE.LocalFree(newAcl);
            }
            if (existingSd != null) {
                Win32SandboxApi.Kernel32Ext.INSTANCE.LocalFree(existingSd);
            }
        }
    }

    private static boolean isIgnorableChildAclError(int win32Error) {
        return win32Error == ERROR_ACCESS_DENIED
                || win32Error == ERROR_FILE_NOT_FOUND
                || win32Error == ERROR_PATH_NOT_FOUND
                || win32Error == ERROR_SHARING_VIOLATION;
    }

    private static boolean hasExactInheritableAllowAce(Pointer dacl, Pointer targetSid, int desiredMask) {
        int requiredFlags = Win32SandboxApi.OBJECT_INHERIT_ACE | Win32SandboxApi.CONTAINER_INHERIT_ACE;
        return hasMatchingAce(dacl, targetSid, desiredMask, requiredFlags, true);
    }

    private static boolean hasExactFileAllowAce(Pointer dacl, Pointer targetSid, int desiredMask) {
        return hasMatchingAce(dacl, targetSid, desiredMask, 0, true);
    }

    private static boolean hasAnyAceForSid(Pointer dacl, Pointer targetSid) {
        return hasMatchingAce(dacl, targetSid, 0, 0, false);
    }

    private static boolean hasMatchingAce(
            Pointer dacl,
            Pointer targetSid,
            int desiredMask,
            int requiredFlags,
            boolean checkMaskAndFlags
    ) {
        Win32SandboxApi.AclSizeInformation sizeInfo = new Win32SandboxApi.AclSizeInformation();
        if (!Win32SandboxApi.Advapi32Ext.INSTANCE.GetAclInformation(
                dacl, sizeInfo, sizeInfo.size(), Win32SandboxApi.ACL_SIZE_INFORMATION_CLASS)) {
            return false;
        }
        for (int i = 0; i < sizeInfo.AceCount; i++) {
            PointerByReference aceRef = new PointerByReference();
            if (!Win32SandboxApi.Advapi32Ext.INSTANCE.GetAce(dacl, i, aceRef) || aceRef.getValue() == null) {
                continue;
            }
            Pointer acePtr = aceRef.getValue();
            Win32SandboxApi.AccessAllowedAce ace = new Win32SandboxApi.AccessAllowedAce(acePtr);
            if (ace.Header.AceType != Win32SandboxApi.ACCESS_ALLOWED_ACE_TYPE) {
                continue;
            }
            if (checkMaskAndFlags) {
                if ((ace.Header.AceFlags & requiredFlags) != requiredFlags || ace.Mask != desiredMask) {
                    continue;
                }
            }
            Pointer aceSid = acePtr.share(8);
            if (Win32SandboxApi.Advapi32Ext.INSTANCE.EqualSid(aceSid, targetSid)) {
                return true;
            }
        }
        return false;
    }
}
