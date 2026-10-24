package com.summit.core.runtime.workspace;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解释器选择只有一处真源：平台白名单、显式请求的解析和 argv 构造都必须在这里定死，
 * 任何"悄悄换成别的 shell"的行为都应让这些测试失败。
 */
class ShellTypeTest {

    @Test
    void blankRequestMeansWorkspaceDefault() {
        assertNull(ShellType.select(null, OsType.WINDOWS));
        assertNull(ShellType.select("", OsType.WINDOWS));
        assertNull(ShellType.select("   ", OsType.LINUX));
    }

    @Test
    void windowsAcceptsCmdPwshAndPowershell() {
        assertEquals(ShellType.CMD, ShellType.select("cmd", OsType.WINDOWS));
        assertEquals(ShellType.PWSH, ShellType.select("pwsh", OsType.WINDOWS));
        assertEquals(ShellType.POWERSHELL, ShellType.select("powershell", OsType.WINDOWS));
        assertEquals(List.of("powershell", "pwsh", "cmd"), ShellType.selectableIds(OsType.WINDOWS));
    }

    @Test
    void linuxAcceptsBashShAndPwsh() {
        assertEquals(ShellType.BASH, ShellType.select("bash", OsType.LINUX));
        assertEquals(ShellType.SH, ShellType.select("sh", OsType.LINUX));
        assertEquals(ShellType.PWSH, ShellType.select("pwsh", OsType.LINUX));
        assertEquals(List.of("pwsh", "bash", "sh"), ShellType.selectableIds(OsType.LINUX));
    }

    @Test
    void requestIsCaseInsensitiveAndTrimmed() {
        assertEquals(ShellType.CMD, ShellType.select(" CMD ", OsType.WINDOWS));
        assertEquals(ShellType.POWERSHELL, ShellType.select("PowerShell", OsType.WINDOWS));
    }

    @Test
    void foreignOrUnknownShellFailsInsteadOfFallingBack() {
        IllegalArgumentException foreign = assertThrows(IllegalArgumentException.class,
                () -> ShellType.select("bash", OsType.WINDOWS));
        assertTrue(foreign.getMessage().contains("bash"), foreign.getMessage());
        assertTrue(foreign.getMessage().contains("cmd"), foreign.getMessage());

        assertThrows(IllegalArgumentException.class, () -> ShellType.select("powershell", OsType.LINUX));
        assertThrows(IllegalArgumentException.class, () -> ShellType.select("sh", OsType.WINDOWS));
        assertThrows(IllegalArgumentException.class, () -> ShellType.select("fish", OsType.LINUX));
        assertThrows(IllegalArgumentException.class, () -> ShellType.select("cmd", OsType.UNKNOWN));
        assertThrows(IllegalArgumentException.class, () -> ShellType.select("cmd", null));
    }

    @Test
    void unknownPlatformOffersNoChoices() {
        assertTrue(ShellType.selectableIds(OsType.UNKNOWN).isEmpty());
        for (ShellType shell : ShellType.values()) {
            assertFalse(shell.isSelectableOn(OsType.UNKNOWN), shell.name());
        }
    }

    @Test
    void cmdArgvKeepsTheScriptAsOneQuotedArgument() {
        List<String> argv = ShellType.CMD.buildCommand("echo \"a b\" & dir");
        assertEquals("cmd.exe", argv.get(0));
        assertEquals(List.of("/d", "/s", "/c"), argv.subList(1, 4));
        assertEquals("\"" + ShellType.UTF8_CODE_PAGE_BOOTSTRAP + "echo \"a b\" & dir\"", argv.get(4));
    }

    @Test
    void posixArgvPassesTheScriptVerbatim() {
        for (ShellType shell : List.of(ShellType.BASH, ShellType.SH, ShellType.ZSH)) {
            String script = "printf '%s\\n' \"a b\" | cat";
            List<String> argv = shell.buildCommand(script);
            assertEquals(3, argv.size(), shell.name());
            assertEquals("-c", argv.get(1));
            assertEquals(script, argv.get(2), shell.name());
        }
    }

    @Test
    void powershellScriptForcesUtf8OutputBeforeTheUserScript() {
        for (ShellType shell : List.of(ShellType.PWSH, ShellType.POWERSHELL)) {
            List<String> argv = shell.buildCommand("Write-Output '中文😀'");
            assertEquals("-EncodedCommand", argv.get(3));
            String script = new String(Base64.getDecoder().decode(argv.get(4)), StandardCharsets.UTF_16LE);
            assertTrue(script.startsWith("[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false);"),
                    shell.name() + " -> " + script);
            assertTrue(script.endsWith("Write-Output '中文😀'"), shell.name() + " -> " + script);
        }
    }

    @Test
    void idsAreTheLowercaseNamesUsedByArgumentsAndSnapshots() {
        assertEquals("cmd", ShellType.CMD.id());
        assertEquals("powershell", ShellType.POWERSHELL.id());
        assertEquals("pwsh", ShellType.PWSH.id());
        assertEquals("bash", ShellType.BASH.id());
    }
}
