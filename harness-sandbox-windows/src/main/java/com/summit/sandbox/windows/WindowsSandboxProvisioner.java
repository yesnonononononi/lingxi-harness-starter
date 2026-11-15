package com.summit.sandbox.windows;

import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Objects;

/**
 * Checks and provisions the dual local Windows sandbox accounts
 * ({@value WindowsSandboxAccount#READ_ONLY_USERNAME} and {@value WindowsSandboxAccount#READ_WRITE_USERNAME})
 * using a single UAC elevation prompt.
 */
public final class WindowsSandboxProvisioner {
    private static final int INFINITE = 0xFFFFFFFF;

    private WindowsSandboxProvisioner() {
    }

    public static boolean isProvisioned() {
        return isProvisioned(
                WindowsSandboxAccount.READ_ONLY_USERNAME,
                WindowsSandboxCredentialStore.defaultCredentialFile(SandboxMode.READ_ONLY)
        ) && isProvisioned(
                WindowsSandboxAccount.READ_WRITE_USERNAME,
                WindowsSandboxCredentialStore.defaultCredentialFile(SandboxMode.READ_WRITE)
        );
    }

    static boolean isProvisioned(String username, Path credentialFile) {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(credentialFile, "credentialFile");
        return WindowsSandboxAccount.lookupSid(username).isPresent()
                && WindowsSandboxCredentialStore.existsAndReadable(credentialFile);
    }

    /**
     * Provisions both read-only and read-write sandbox accounts with DPAPI-protected random passwords.
     * Triggers a single UAC elevation prompt if either account or credential is missing.
     */
    public static void provisionIfNeeded() throws IOException, InterruptedException {
        provisionIfNeeded(
                WindowsSandboxAccount.READ_ONLY_USERNAME,
                WindowsSandboxCredentialStore.defaultCredentialFile(SandboxMode.READ_ONLY),
                WindowsSandboxAccount.READ_WRITE_USERNAME,
                WindowsSandboxCredentialStore.defaultCredentialFile(SandboxMode.READ_WRITE)
        );
    }

    static void provisionIfNeeded(
            String roUsername,
            Path roCredentialFile,
            String rwUsername,
            Path rwCredentialFile
    ) throws IOException, InterruptedException {
        if (isProvisioned(roUsername, roCredentialFile) && isProvisioned(rwUsername, rwCredentialFile)) {
            return;
        }
        String roPassword = WindowsSandboxCredentialStore.generateStrongPassword();
        String rwPassword = WindowsSandboxCredentialStore.generateStrongPassword();
        WindowsSandboxCredentialStore.save(roCredentialFile, roPassword);
        WindowsSandboxCredentialStore.save(rwCredentialFile, rwPassword);

        String script = buildEnsureUserScript(roUsername, roPassword, "LingXi Read-Only Sandbox Account")
                + buildEnsureUserScript(rwUsername, rwPassword, "LingXi Read-Write Sandbox Account");
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));

        Win32SandboxApi.ShellExecuteInfo sei = new Win32SandboxApi.ShellExecuteInfo();
        sei.fMask = Win32SandboxApi.SEE_MASK_NOCLOSEPROCESS;
        sei.lpVerb = "runas";
        sei.lpFile = "powershell.exe";
        sei.lpParameters = "-NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand " + encoded;
        sei.nShow = Win32SandboxApi.SW_HIDE;

        boolean launched = Win32SandboxApi.Shell32Ext.INSTANCE.ShellExecuteExW(sei);
        if (!launched || sei.hProcess == null) {
            Files.deleteIfExists(roCredentialFile);
            Files.deleteIfExists(rwCredentialFile);
            int err = Win32SandboxApi.Kernel32Ext.INSTANCE.GetLastError();
            throw new IllegalStateException("UAC elevation failed or cancelled during sandbox provisioning: win32=" + err);
        }

        WinNT.HANDLE process = sei.hProcess;
        try {
            Win32SandboxApi.Kernel32Ext.INSTANCE.WaitForSingleObject(process, INFINITE);
            IntByReference exitCode = new IntByReference(-1);
            Win32SandboxApi.Kernel32Ext.INSTANCE.GetExitCodeProcess(process, exitCode);
            if (exitCode.getValue() != 0) {
                Files.deleteIfExists(roCredentialFile);
                Files.deleteIfExists(rwCredentialFile);
                throw new IllegalStateException("Sandbox account provisioning failed with exitCode=" + exitCode.getValue());
            }
        } finally {
            Win32SandboxApi.Kernel32Ext.INSTANCE.CloseHandle(process);
        }

        if (!isProvisioned(roUsername, roCredentialFile) || !isProvisioned(rwUsername, rwCredentialFile)) {
            throw new IllegalStateException("Sandbox account provisioning completed but verification failed");
        }
    }

    private static String buildEnsureUserScript(String username, String password, String description) {
        String escapedUser = username.replace("'", "''");
        String escapedPassword = password.replace("'", "''");
        String escapedDesc = description.replace("'", "''");
        return "$u = '" + escapedUser + "';"
                + "$p = ConvertTo-SecureString '" + escapedPassword + "' -AsPlainText -Force;"
                + "if (Get-LocalUser -Name $u -ErrorAction SilentlyContinue) {"
                + "  Set-LocalUser -Name $u -Password $p -PasswordNeverExpires $true -UserMayChangePassword $false;"
                + "} else {"
                + "  New-LocalUser -Name $u -Password $p -PasswordNeverExpires -UserMayChangePassword:$false -Description '" + escapedDesc + "' | Out-Null;"
                + "}"
                + "if (-not (Get-LocalGroupMember -Group 'Users' -Member $u -ErrorAction SilentlyContinue)) {"
                + "  Add-LocalGroupMember -Group 'Users' -Member $u;"
                + "}";
    }
}
