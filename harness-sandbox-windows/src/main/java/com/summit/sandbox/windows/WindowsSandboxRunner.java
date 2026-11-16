package com.summit.sandbox.windows;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.summit.core.runtime.CommandHandler;
import com.summit.core.runtime.workspace.WorkspaceCommands.CommandResult;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Executes commands inside a Windows Restricted Token sandbox ({@code CreateRestrictedToken}
 * + {@code CreateProcessAsUserW}) with Job Object process-tree cleanup.
 * <p>
 * By keeping the primary {@code TokenUser} SID identical to the host user while restricting
 * access via capability restricting SIDs ({@code SidsToRestrict}), tools that verify file
 * ownership against the current process user SID (such as {@code git}, {@code ssh}, {@code gpg})
 * work natively without SID mismatch errors or reverse ownership pollution.
 */
public final class WindowsSandboxRunner {
    private static final String WORLD_SID = "S-1-1-0";
    private static final String LOCAL_SID = "S-1-2-0";
    private static final String RESTRICTED_CODE_SID = "S-1-5-12";
    private static final String BUILTIN_ADMINS_SID = "S-1-5-32-544";
    private static final String BUILTIN_USERS_SID = "S-1-5-32-545";

    private WindowsSandboxRunner() {
    }

    public static SandboxResult run(
            Path workspaceRoot,
            SandboxMode mode,
            List<String> command,
            Duration timeout
    ) throws IOException, InterruptedException {
        return run(workspaceRoot, mode, command, Map.of(), timeout, StandardCharsets.UTF_8);
    }

    public static SandboxResult run(
            Path workspaceRoot,
            SandboxMode mode,
            List<String> command,
            Map<String, String> extraEnv,
            Duration timeout,
            Charset outputCharset
    ) throws IOException, InterruptedException {
        CommandHandler handle = start(
                workspaceRoot,
                workspaceRoot,
                mode,
                command,
                extraEnv,
                timeout,
                outputCharset,
                0L
        );
        CommandResult result = handle.await();
        return new SandboxResult(result.exitCode(), result.output(), result.timedOut());
    }

    static CommandHandler start(
            Path workspaceRoot,
            Path workingDirectory,
            SandboxMode mode,
            List<String> command,
            Map<String, String> extraEnv,
            Duration timeout,
            Charset outputCharset,
            long maxChars
    ) throws IOException {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(outputCharset, "outputCharset");
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        Path normalizedRoot = workspaceRoot.toAbsolutePath().normalize();
        Path normalizedWorkDir = workingDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalizedRoot)) {
            throw new IllegalArgumentException("Workspace directory does not exist: " + normalizedRoot);
        }
        if (!normalizedWorkDir.startsWith(normalizedRoot) || !Files.isDirectory(normalizedWorkDir)) {
            throw new IllegalArgumentException("Working directory must reside inside workspace: " + normalizedWorkDir);
        }

        String profileKey = WindowsSandboxAccount.profileFor(mode);
        ensureSandboxProfileDir(profileKey, mode);
        WindowsWorkspaceAcl.ensureWorkspacePermission(normalizedRoot, mode);
        return launchWithRestrictedToken(
                normalizedRoot,
                normalizedWorkDir,
                mode,
                profileKey,
                command,
                extraEnv,
                timeout,
                outputCharset,
                maxChars
        );
    }

    private static CommandHandler launchWithRestrictedToken(
            Path workspaceRoot,
            Path workingDirectory,
            SandboxMode mode,
            String profileKey,
            List<String> command,
            Map<String, String> extraEnv,
            Duration timeout,
            Charset outputCharset,
            long maxChars
    ) {
        WinNT.HANDLE jobHandle = null;
        WinNT.HANDLE outRead = null;
        WinNT.HANDLE outWrite = null;
        WinNT.HANDLE inRead = null;
        WinNT.HANDLE inWrite = null;
        WinNT.HANDLE restrictedToken = null;
        WinBase.PROCESS_INFORMATION pi = new WinBase.PROCESS_INFORMATION();
        boolean handoff = false;

        try {
            jobHandle = createKillOnCloseJob();

            WinBase.SECURITY_ATTRIBUTES sa = new WinBase.SECURITY_ATTRIBUTES();
            sa.dwLength = new WinDef.DWORD(sa.size());
            sa.bInheritHandle = true;
            sa.lpSecurityDescriptor = null;

            WinNT.HANDLEByReference outReadRef = new WinNT.HANDLEByReference();
            WinNT.HANDLEByReference outWriteRef = new WinNT.HANDLEByReference();
            if (!Win32SandboxApi.Kernel32Ext.INSTANCE.CreatePipe(outReadRef, outWriteRef, sa, 0)) {
                throw lastError("CreatePipe(stdout)");
            }
            outRead = outReadRef.getValue();
            outWrite = outWriteRef.getValue();
            if (!Win32SandboxApi.Kernel32Ext.INSTANCE.SetHandleInformation(outRead, WinBase.HANDLE_FLAG_INHERIT, 0)) {
                throw lastError("SetHandleInformation(outRead)");
            }

            WinNT.HANDLEByReference inReadRef = new WinNT.HANDLEByReference();
            WinNT.HANDLEByReference inWriteRef = new WinNT.HANDLEByReference();
            if (!Win32SandboxApi.Kernel32Ext.INSTANCE.CreatePipe(inReadRef, inWriteRef, sa, 0)) {
                throw lastError("CreatePipe(stdin)");
            }
            inRead = inReadRef.getValue();
            inWrite = inWriteRef.getValue();
            if (!Win32SandboxApi.Kernel32Ext.INSTANCE.SetHandleInformation(inWrite, WinBase.HANDLE_FLAG_INHERIT, 0)) {
                throw lastError("SetHandleInformation(inWrite)");
            }

            WinBase.STARTUPINFO si = new WinBase.STARTUPINFO();
            si.cb = new WinDef.DWORD(si.size());
            si.dwFlags = WinBase.STARTF_USESTDHANDLES;
            si.hStdInput = inRead;
            si.hStdOutput = outWrite;
            si.hStdError = outWrite;

            restrictedToken = createRestrictedSandboxToken(workspaceRoot, mode);
            String commandLine = buildCommandLine(command);
            char[] cmdChars = Arrays.copyOf(commandLine.toCharArray(), commandLine.length() + 1);
            Memory envBlock = buildEnvironmentBlock(workspaceRoot, workingDirectory, profileKey, extraEnv);

            int creationFlags = Win32SandboxApi.CREATE_SUSPENDED
                    | Win32SandboxApi.CREATE_UNICODE_ENVIRONMENT
                    | Win32SandboxApi.CREATE_NO_WINDOW;

            boolean started = Win32SandboxApi.Advapi32Ext.INSTANCE.CreateProcessAsUserW(
                    restrictedToken,
                    null,
                    cmdChars,
                    null,
                    null,
                    true,
                    creationFlags,
                    envBlock,
                    workingDirectory.toString(),
                    si,
                    pi
            );
            if (!started) {
                throw lastError("CreateProcessAsUserW");
            }

            closeQuietly(outWrite);
            outWrite = null;
            closeQuietly(inRead);
            inRead = null;
            closeQuietly(inWrite);
            inWrite = null;

            Memory workspaceSid = WindowsSandboxAccount.workspaceSid(workspaceRoot, mode);
            grantSidOnKernelObject(pi.hProcess, workspaceSid);
            grantSidOnKernelObject(pi.hThread, workspaceSid);

            if (!Win32SandboxApi.Kernel32Ext.INSTANCE.AssignProcessToJobObject(jobHandle, pi.hProcess)) {
                Win32SandboxApi.Kernel32Ext.INSTANCE.TerminateJobObject(jobHandle, 1);
                throw lastError("AssignProcessToJobObject");
            }
            if (Win32SandboxApi.Kernel32Ext.INSTANCE.ResumeThread(pi.hThread) == -1) {
                Win32SandboxApi.Kernel32Ext.INSTANCE.TerminateJobObject(jobHandle, 1);
                throw lastError("ResumeThread");
            }

            handoff = true;
            return new WindowsCommandHandle(
                    jobHandle,
                    pi.hProcess,
                    pi.hThread,
                    outRead,
                    outputCharset,
                    maxChars,
                    timeout
            );
        } finally {
            closeQuietly(restrictedToken);
            closeQuietly(outWrite);
            closeQuietly(inRead);
            closeQuietly(inWrite);
            if (!handoff) {
                closeQuietly(pi.hThread);
                closeQuietly(pi.hProcess);
                closeQuietly(outRead);
                closeQuietly(jobHandle);
            }
        }
    }

    private static WinNT.HANDLE createKillOnCloseJob() {
        WinNT.HANDLE jobHandle = Win32SandboxApi.Kernel32Ext.INSTANCE.CreateJobObjectW(null, null);
        if (jobHandle == null) {
            throw lastError("CreateJobObjectW");
        }
        Win32SandboxApi.JobObjectExtendedLimitInformation jobInfo =
                new Win32SandboxApi.JobObjectExtendedLimitInformation();
        jobInfo.BasicLimitInformation.LimitFlags = Win32SandboxApi.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        jobInfo.write();
        if (!Win32SandboxApi.Kernel32Ext.INSTANCE.SetInformationJobObject(
                jobHandle,
                Win32SandboxApi.JOB_OBJECT_EXTENDED_LIMIT_INFORMATION_CLASS,
                jobInfo.getPointer(),
                jobInfo.size())) {
            closeQuietly(jobHandle);
            throw lastError("SetInformationJobObject");
        }
        return jobHandle;
    }

    private static WinNT.HANDLE createRestrictedSandboxToken(Path workspaceRoot, SandboxMode mode) {
        WinNT.HANDLEByReference processTokenRef = new WinNT.HANDLEByReference();
        int desiredAccess = Win32SandboxApi.TOKEN_DUPLICATE
                | Win32SandboxApi.TOKEN_ASSIGN_PRIMARY
                | Win32SandboxApi.TOKEN_QUERY
                | Win32SandboxApi.TOKEN_ADJUST_DEFAULT;
        if (!Win32SandboxApi.Advapi32Ext.INSTANCE.OpenProcessToken(
                Win32SandboxApi.Kernel32Ext.INSTANCE.GetCurrentProcess(),
                desiredAccess,
                processTokenRef)) {
            throw lastError("OpenProcessToken");
        }
        WinNT.HANDLE processToken = processTokenRef.getValue();
        try {
            Memory workspaceSid = WindowsSandboxAccount.workspaceSid(workspaceRoot, mode);
            Memory profileSid = WindowsSandboxAccount.sandboxSid(mode);
            List<Memory> restrictingSids = new ArrayList<>(7);
            restrictingSids.add(workspaceSid);
            restrictingSids.add(profileSid);
            restrictingSids.add(WindowsSandboxAccount.sidFromString(WORLD_SID));
            restrictingSids.add(WindowsSandboxAccount.sidFromString(BUILTIN_USERS_SID));
            restrictingSids.add(WindowsSandboxAccount.sidFromString(RESTRICTED_CODE_SID));
            restrictingSids.add(WindowsSandboxAccount.sidFromString(LOCAL_SID));

            List<Memory> disableSids = new ArrayList<>(1);
            Memory adminsSid = WindowsSandboxAccount.sidFromString(BUILTIN_ADMINS_SID);
            inspectTokenGroups(processToken, adminsSid, disableSids, restrictingSids);

            Win32SandboxApi.SidAndAttributes[] sidsToDisable = null;
            if (!disableSids.isEmpty()) {
                Win32SandboxApi.SidAndAttributes firstDisable = new Win32SandboxApi.SidAndAttributes();
                sidsToDisable = (Win32SandboxApi.SidAndAttributes[]) firstDisable.toArray(disableSids.size());
                for (int i = 0; i < disableSids.size(); i++) {
                    sidsToDisable[i].Sid = disableSids.get(i);
                    sidsToDisable[i].Attributes = 0;
                    sidsToDisable[i].write();
                }
            }

            Win32SandboxApi.SidAndAttributes first = new Win32SandboxApi.SidAndAttributes();
            Win32SandboxApi.SidAndAttributes[] sidsToRestrict =
                    (Win32SandboxApi.SidAndAttributes[]) first.toArray(restrictingSids.size());
            for (int i = 0; i < restrictingSids.size(); i++) {
                sidsToRestrict[i].Sid = restrictingSids.get(i);
                sidsToRestrict[i].Attributes = 0;
                sidsToRestrict[i].write();
            }

            WinNT.HANDLEByReference restrictedTokenRef = new WinNT.HANDLEByReference();
            if (!Win32SandboxApi.Advapi32Ext.INSTANCE.CreateRestrictedToken(
                    processToken,
                    Win32SandboxApi.DISABLE_MAX_PRIVILEGE,
                    sidsToDisable == null ? 0 : sidsToDisable.length,
                    sidsToDisable,
                    0,
                    null,
                    sidsToRestrict.length,
                    sidsToRestrict,
                    restrictedTokenRef)) {
                throw lastError("CreateRestrictedToken");
            }
            WinNT.HANDLE restrictedToken = restrictedTokenRef.getValue();
            boolean configured = false;
            try {
                grantSidOnTokenAndDefaultDacl(restrictedToken, workspaceSid);
                configured = true;
                return restrictedToken;
            } finally {
                if (!configured) {
                    closeQuietly(restrictedToken);
                }
            }
        } finally {
            closeQuietly(processToken);
        }
    }

    private static void inspectTokenGroups(
            WinNT.HANDLE token,
            Pointer adminsSid,
            List<Memory> disableSids,
            List<Memory> restrictingSids
    ) {
        IntByReference needed = new IntByReference(0);
        Win32SandboxApi.Advapi32Ext.INSTANCE.GetTokenInformation(
                token, Win32SandboxApi.TOKEN_GROUPS_CLASS, null, 0, needed);
        int size = needed.getValue();
        if (size <= 0) {
            return;
        }
        Memory buffer = new Memory(size);
        if (!Win32SandboxApi.Advapi32Ext.INSTANCE.GetTokenInformation(
                token, Win32SandboxApi.TOKEN_GROUPS_CLASS, buffer, size, needed)) {
            return;
        }
        int groupCount = buffer.getInt(0);
        int offset = Native.POINTER_SIZE;
        int stride = Native.POINTER_SIZE * 2;
        for (int i = 0; i < groupCount; i++) {
            Pointer sidPtr = buffer.getPointer((long) offset + (long) i * stride);
            int attributes = buffer.getInt((long) offset + (long) i * stride + Native.POINTER_SIZE);
            if (sidPtr == null) {
                continue;
            }
            if ((attributes & Win32SandboxApi.SE_GROUP_LOGON_ID) != 0) {
                restrictingSids.add(WindowsSandboxAccount.copySidToMemory(sidPtr));
            } else if (Win32SandboxApi.Advapi32Ext.INSTANCE.EqualSid(sidPtr, adminsSid)) {
                disableSids.add(WindowsSandboxAccount.copySidToMemory(sidPtr));
            }
        }
    }

    private static void grantSidOnKernelObject(WinNT.HANDLE kernelObject, Pointer sandboxSid) {
        Win32SandboxApi.ExplicitAccess entry = new Win32SandboxApi.ExplicitAccess();
        entry.grfAccessPermissions = Win32SandboxApi.GENERIC_ALL;
        entry.grfAccessMode = Win32SandboxApi.SET_ACCESS;
        entry.grfInheritance = Win32SandboxApi.NO_INHERITANCE;
        entry.Trustee.pMultipleTrustee = null;
        entry.Trustee.MultipleTrusteeOperation = Win32SandboxApi.NO_MULTIPLE_TRUSTEE;
        entry.Trustee.TrusteeForm = Win32SandboxApi.TRUSTEE_IS_SID;
        entry.Trustee.TrusteeType = Win32SandboxApi.TRUSTEE_IS_UNKNOWN;
        entry.Trustee.ptstrName = sandboxSid;
        Win32SandboxApi.ExplicitAccess[] entries = new Win32SandboxApi.ExplicitAccess[]{entry};

        PointerByReference daclRef = new PointerByReference();
        PointerByReference sdRef = new PointerByReference();
        int getStatus = Win32SandboxApi.Advapi32Ext.INSTANCE.GetSecurityInfo(
                kernelObject,
                Win32SandboxApi.SE_KERNEL_OBJECT,
                Win32SandboxApi.DACL_SECURITY_INFORMATION,
                null,
                null,
                daclRef,
                null,
                sdRef
        );
        if (getStatus == 0) {
            Pointer sd = sdRef.getValue();
            Pointer newDacl = null;
            try {
                PointerByReference newAclRef = new PointerByReference();
                if (Win32SandboxApi.Advapi32Ext.INSTANCE.SetEntriesInAclW(1, entries, daclRef.getValue(), newAclRef) == 0
                        && newAclRef.getValue() != null) {
                    newDacl = newAclRef.getValue();
                    Win32SandboxApi.Advapi32Ext.INSTANCE.SetSecurityInfo(
                            kernelObject,
                            Win32SandboxApi.SE_KERNEL_OBJECT,
                            Win32SandboxApi.DACL_SECURITY_INFORMATION,
                            null,
                            null,
                            newDacl,
                            null
                    );
                }
            } finally {
                if (newDacl != null) {
                    Win32SandboxApi.Kernel32Ext.INSTANCE.LocalFree(newDacl);
                }
                if (sd != null) {
                    Win32SandboxApi.Kernel32Ext.INSTANCE.LocalFree(sd);
                }
            }
        }
    }

    private static void grantSidOnTokenAndDefaultDacl(WinNT.HANDLE restrictedToken, Pointer sandboxSid) {
        grantSidOnKernelObject(restrictedToken, sandboxSid);

        Win32SandboxApi.ExplicitAccess entry = new Win32SandboxApi.ExplicitAccess();
        entry.grfAccessPermissions = Win32SandboxApi.GENERIC_ALL;
        entry.grfAccessMode = Win32SandboxApi.SET_ACCESS;
        entry.grfInheritance = Win32SandboxApi.NO_INHERITANCE;
        entry.Trustee.pMultipleTrustee = null;
        entry.Trustee.MultipleTrusteeOperation = Win32SandboxApi.NO_MULTIPLE_TRUSTEE;
        entry.Trustee.TrusteeForm = Win32SandboxApi.TRUSTEE_IS_SID;
        entry.Trustee.TrusteeType = Win32SandboxApi.TRUSTEE_IS_UNKNOWN;
        entry.Trustee.ptstrName = sandboxSid;
        Win32SandboxApi.ExplicitAccess[] entries = new Win32SandboxApi.ExplicitAccess[]{entry};

        // Grant sandboxSid GENERIC_ALL in TokenDefaultDacl so grandchild processes/threads/pipes created inside the sandbox pass Pass 2 checks.
        IntByReference needed = new IntByReference(0);
        Win32SandboxApi.Advapi32Ext.INSTANCE.GetTokenInformation(
                restrictedToken, Win32SandboxApi.TOKEN_DEFAULT_DACL_CLASS, null, 0, needed);
        if (needed.getValue() > 0) {
            Memory daclBuf = new Memory(needed.getValue());
            if (Win32SandboxApi.Advapi32Ext.INSTANCE.GetTokenInformation(
                    restrictedToken, Win32SandboxApi.TOKEN_DEFAULT_DACL_CLASS, daclBuf, (int) daclBuf.size(), needed)) {
                Pointer existingDefaultDacl = daclBuf.getPointer(0);
                PointerByReference newDefaultAclRef = new PointerByReference();
                if (Win32SandboxApi.Advapi32Ext.INSTANCE.SetEntriesInAclW(1, entries, existingDefaultDacl, newDefaultAclRef) == 0
                        && newDefaultAclRef.getValue() != null) {
                    Pointer newDefaultAcl = newDefaultAclRef.getValue();
                    try {
                        Win32SandboxApi.TokenDefaultDacl defaultDaclStruct = new Win32SandboxApi.TokenDefaultDacl();
                        defaultDaclStruct.DefaultDacl = newDefaultAcl;
                        defaultDaclStruct.write();
                        Win32SandboxApi.Advapi32Ext.INSTANCE.SetTokenInformation(
                                restrictedToken,
                                Win32SandboxApi.TOKEN_DEFAULT_DACL_CLASS,
                                defaultDaclStruct.getPointer(),
                                defaultDaclStruct.size()
                        );
                    } finally {
                        Win32SandboxApi.Kernel32Ext.INSTANCE.LocalFree(newDefaultAcl);
                    }
                }
            }
        }
    }

    static Path sandboxProfileDir(String profileKey) {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path base = (localAppData != null && !localAppData.isBlank())
                ? Path.of(localAppData)
                : Path.of(System.getProperty("user.home"), "AppData", "Local");
        return base.resolve("LingXi").resolve("sandbox-profiles").resolve(profileKey);
    }

    private static void ensureSandboxProfileDir(String profileKey, SandboxMode mode) throws IOException {
        Path profileDir = sandboxProfileDir(profileKey);
        Files.createDirectories(profileDir.resolve("AppData").resolve("Roaming"));
        Files.createDirectories(profileDir.resolve("AppData").resolve("Local").resolve("Temp"));
        Path hostGitConfig = Path.of(System.getProperty("user.home"), ".gitconfig");
        Path sandboxGitConfig = profileDir.resolve(".gitconfig");
        if (Files.isRegularFile(hostGitConfig) && !Files.exists(sandboxGitConfig)) {
            Files.copy(hostGitConfig, sandboxGitConfig, StandardCopyOption.REPLACE_EXISTING);
        }
        WindowsWorkspaceAcl.ensureProfilePermission(profileDir, mode);
    }

    private static Memory buildEnvironmentBlock(
            Path workspaceRoot,
            Path workingDirectory,
            String profileKey,
            Map<String, String> extraEnv
    ) {
        // Win32 CREATE_UNICODE_ENVIRONMENT requires case-insensitive alphabetical key ordering
        // and no duplicate case-insensitive variable names (e.g. "Path" vs "PATH").
        Map<String, String> merged = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key != null && !key.isEmpty() && key.charAt(0) != '=' && value != null) {
                merged.put(key, value);
            }
        }
        String root = workspaceRoot.toString();
        Path profilePath = sandboxProfileDir(profileKey).toAbsolutePath().normalize();
        String profile = profilePath.toString();
        Path profileRoot = profilePath.getRoot();
        String homeDrive = profileRoot != null
                ? profileRoot.toString().replace("\\", "")
                : Objects.requireNonNullElse(System.getenv("SystemDrive"), "C:");
        String homePath = profileRoot != null
                ? "\\" + profileRoot.relativize(profilePath)
                : "\\Users\\" + profileKey;
        merged.put("HARNESS_WORKSPACE", root);
        merged.put("WORKDIR", workingDirectory.toString());
        merged.put("HOME", profile);
        merged.put("USERPROFILE", profile);
        merged.put("HOMEDRIVE", homeDrive);
        merged.put("HOMEPATH", homePath);
        merged.put("APPDATA", profile + "\\AppData\\Roaming");
        merged.put("LOCALAPPDATA", profile + "\\AppData\\Local");
        merged.put("TEMP", profile + "\\AppData\\Local\\Temp");
        merged.put("TMP", profile + "\\AppData\\Local\\Temp");
        if (extraEnv != null) {
            for (Map.Entry<String, String> entry : extraEnv.entrySet()) {
                if (entry.getKey() != null && !entry.getKey().isEmpty() && entry.getValue() != null) {
                    merged.put(entry.getKey(), entry.getValue());
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : merged.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue()).append('\0');
        }
        sb.append('\0');
        char[] chars = sb.toString().toCharArray();
        Memory memory = new Memory((long) chars.length * 2L);
        memory.write(0, chars, 0, chars.length);
        return memory;
    }

    static String buildCommandLine(List<String> command) {
        boolean isCmd = !command.isEmpty() && isCmdExecutable(command.get(0));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < command.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            String arg = command.get(i);
            // Match JDK ProcessImpl: cmd.exe /c "..." expects the outer-quoted script verbatim without C-runtime \" escaping.
            if (isCmd && i == command.size() - 1 && arg.length() >= 2 && arg.startsWith("\"") && arg.endsWith("\"")) {
                sb.append(arg);
            } else {
                sb.append(quoteWindowsArg(arg));
            }
        }
        return sb.toString();
    }

    private static boolean isCmdExecutable(String executable) {
        String name = Path.of(executable).getFileName().toString();
        return "cmd.exe".equalsIgnoreCase(name) || "cmd".equalsIgnoreCase(name);
    }

    private static String quoteWindowsArg(String arg) {
        if (!arg.isEmpty() && arg.indexOf(' ') < 0 && arg.indexOf('\t') < 0 && arg.indexOf('"') < 0) {
            return arg;
        }
        StringBuilder sb = new StringBuilder("\"");
        int backslashes = 0;
        for (int i = 0; i < arg.length(); i++) {
            char c = arg.charAt(i);
            if (c == '\\') {
                backslashes++;
            } else if (c == '"') {
                sb.repeat("\\", backslashes * 2 + 1).append('"');
                backslashes = 0;
            } else {
                sb.repeat("\\", backslashes).append(c);
                backslashes = 0;
            }
        }
        sb.repeat("\\", backslashes * 2).append('"');
        return sb.toString();
    }

    private static IllegalStateException lastError(String api) {
        int err = Win32SandboxApi.Kernel32Ext.INSTANCE.GetLastError();
        return new IllegalStateException(api + " failed: win32=" + err);
    }

    private static void closeQuietly(WinNT.HANDLE handle) {
        if (handle != null && !WinBase.INVALID_HANDLE_VALUE.equals(handle)) {
            Win32SandboxApi.Kernel32Ext.INSTANCE.CloseHandle(handle);
        }
    }
}
