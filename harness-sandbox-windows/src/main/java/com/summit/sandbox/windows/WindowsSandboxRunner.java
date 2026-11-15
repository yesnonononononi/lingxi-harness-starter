package com.summit.sandbox.windows;

import com.sun.jna.Memory;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.summit.core.runtime.CommandHandler;
import com.summit.core.runtime.workspace.WorkspaceCommands.CommandResult;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Executes commands under the dedicated low-privilege Windows sandbox accounts
 * via {@code CreateProcessWithLogonW} with Job Object process-tree cleanup.
 */
public final class WindowsSandboxRunner {
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
        return run(
                workspaceRoot,
                mode,
                command,
                extraEnv,
                timeout,
                outputCharset,
                WindowsSandboxAccount.usernameFor(mode),
                WindowsSandboxCredentialStore.defaultCredentialFile(mode)
        );
    }

    static SandboxResult run(
            Path workspaceRoot,
            SandboxMode mode,
            List<String> command,
            Map<String, String> extraEnv,
            Duration timeout,
            Charset outputCharset,
            String username,
            Path credentialFile
    ) throws IOException, InterruptedException {
        CommandHandler handle = start(
                workspaceRoot,
                workspaceRoot,
                mode,
                command,
                extraEnv,
                timeout,
                outputCharset,
                0L,
                username,
                credentialFile
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
            long maxChars,
            String username,
            Path credentialFile
    ) throws IOException {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(outputCharset, "outputCharset");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(credentialFile, "credentialFile");
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
        if (!WindowsSandboxProvisioner.isProvisioned(username, credentialFile)) {
            throw new IllegalStateException(
                    "Windows sandbox account '" + username + "' is not provisioned. Call WindowsSandboxProvisioner.provisionIfNeeded() first.");
        }

        String password = WindowsSandboxCredentialStore.load(credentialFile);
        WindowsWorkspaceAcl.ensureWorkspacePermission(normalizedRoot, username, mode);
        return launchAsSandboxUser(
                normalizedRoot,
                normalizedWorkDir,
                username,
                password,
                command,
                extraEnv,
                timeout,
                outputCharset,
                maxChars
        );
    }

    private static CommandHandler launchAsSandboxUser(
            Path workspaceRoot,
            Path workingDirectory,
            String username,
            String password,
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
        WinBase.PROCESS_INFORMATION pi = new WinBase.PROCESS_INFORMATION();
        boolean handoff = false;

        try {
            jobHandle = Win32SandboxApi.Kernel32Ext.INSTANCE.CreateJobObjectW(null, null);
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
                throw lastError("SetInformationJobObject");
            }

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

            String commandLine = buildCommandLine(command);
            char[] cmdChars = Arrays.copyOf(commandLine.toCharArray(), commandLine.length() + 1);
            Memory envBlock = buildEnvironmentBlock(workspaceRoot, workingDirectory, extraEnv);

            int creationFlags = Win32SandboxApi.CREATE_SUSPENDED
                    | Win32SandboxApi.CREATE_UNICODE_ENVIRONMENT
                    | Win32SandboxApi.CREATE_NO_WINDOW;

            boolean started = Win32SandboxApi.Advapi32Ext.INSTANCE.CreateProcessWithLogonW(
                    username,
                    ".",
                    password,
                    Win32SandboxApi.LOGON_WITH_PROFILE,
                    null,
                    cmdChars,
                    creationFlags,
                    envBlock,
                    workingDirectory.toString(),
                    si,
                    pi
            );
            if (!started) {
                throw lastError("CreateProcessWithLogonW");
            }

            closeQuietly(outWrite);
            outWrite = null;
            closeQuietly(inRead);
            inRead = null;
            closeQuietly(inWrite);
            inWrite = null;

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

    private static Memory buildEnvironmentBlock(
            Path workspaceRoot,
            Path workingDirectory,
            Map<String, String> extraEnv
    ) {
        Map<String, String> merged = new LinkedHashMap<>(System.getenv());
        String root = workspaceRoot.toString();
        merged.put("HARNESS_WORKSPACE", root);
        merged.put("WORKDIR", workingDirectory.toString());
        merged.put("HOME", root);
        merged.put("USERPROFILE", root);
        merged.put("LOCALAPPDATA", root);
        merged.put("TEMP", root);
        merged.put("TMP", root);
        if (extraEnv != null) {
            merged.putAll(extraEnv);
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

    private static String buildCommandLine(List<String> command) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < command.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(quoteWindowsArg(command.get(i)));
        }
        return sb.toString();
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
                sb.append("\\".repeat(backslashes * 2 + 1)).append('"');
                backslashes = 0;
            } else {
                sb.append("\\".repeat(backslashes)).append(c);
                backslashes = 0;
            }
        }
        sb.append("\\".repeat(backslashes * 2)).append('"');
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
