package com.summit.core.runtime.workspace;

import com.summit.core.runtime.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三种解释器共用一条 UTF-8 输出链路：宿主按 UTF-8 解码，解释器必须先把输出代码页切到 65001。
 * 这里用真实解释器验证中文、emoji、标准错误和截断，避免出现"单元测试都过、真跑全是乱码"。
 */
class ShellInterpreterEncodingTest {

    private static final String SAMPLE = "中文😀";

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void cmdDecodesNonAsciiProgramOutputAsUtf8() throws Exception {
        Path file = Files.createTempFile("cmd-encoding", ".txt");
        try {
            Files.writeString(file, SAMPLE + "\r\n", StandardCharsets.UTF_8);
            WorkspaceCommands.CommandResult result = run(ShellType.CMD, "type \"" + file + "\"");
            assertEquals(0, result.exitCode(), result.output());
            assertEquals(SAMPLE, result.output().strip());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void cmdPassesNonAsciiCommandTextToProgramsIntact() throws Exception {
        // 实测结论：命令文本经 CreateProcessW 以 UTF-16 传给子进程，完全无损；
        // 变成乱码的是 cmd 内置命令（echo 等）的输出，不是命令文本本身。
        WorkspaceCommands.CommandResult result = run(ShellType.CMD,
                "node -e \"process.stdout.write([...process.argv[1]].map(c => c.codePointAt(0).toString(16)).join(','))\" "
                        + SAMPLE);
        assertEquals(0, result.exitCode(), result.output());
        assertEquals("4e2d,6587,1f600", result.output().strip());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsPowerShellKeepsNonAsciiScriptAndStderrIntact() throws Exception {
        WorkspaceCommands.CommandResult out = run(ShellType.POWERSHELL, "[Console]::Write('" + SAMPLE + "')");
        assertEquals(0, out.exitCode(), out.output());
        assertEquals(SAMPLE, out.output().strip());

        WorkspaceCommands.CommandResult err = run(ShellType.POWERSHELL,
                "[Console]::Error.Write('中文错误'); exit 3");
        assertEquals(3, err.exitCode(), err.output());
        assertTrue(err.output().contains("中文错误"), err.output());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void pwshSharesTheSameContractWhenInstalled() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(available(ShellType.PWSH),
                "pwsh is not installed on this host");
        WorkspaceCommands.CommandResult result = run(ShellType.PWSH, "[Console]::Write('" + SAMPLE + "')");
        assertEquals(0, result.exitCode(), result.output());
        assertEquals(SAMPLE, result.output().strip());
    }

    @Test
    void posixShellsKeepNonAsciiScriptAndStderrIntact() throws Exception {
        for (ShellType shell : new ShellType[]{ShellType.BASH, ShellType.SH}) {
            org.junit.jupiter.api.Assumptions.assumeTrue(available(shell), shell + " is not installed");
            WorkspaceCommands.CommandResult result = run(shell, "printf '%s' '" + SAMPLE + "'");
            assertEquals(0, result.exitCode(), result.output());
            assertEquals(SAMPLE, result.output().strip());

            WorkspaceCommands.CommandResult err = run(shell, "printf '中文错误' 1>&2; exit 4");
            assertEquals(4, err.exitCode(), err.output());
            assertTrue(err.output().contains("中文错误"), err.output());
        }
    }

    @Test
    void truncationNeverSplitsAMultiByteCharacter() throws Exception {
        // 用 node 直接写 UTF-8 字节，绕开 cmd 控制台按块转码（那会把 emoji 自己拆坏），
        // 这样截断点是否切断代理对只取决于框架的截断逻辑。
        List<String> argv = List.of("node", "-e", "process.stdout.write('中文😀'.repeat(400))");
        WorkspaceCommands.CommandResult result = ProcessRunner.run(argv, null, StandardCharsets.UTF_8, 60, 39);
        assertTrue(result.truncated(), "输出应当被截断");
        // 预算 39 落在 emoji 的代理对中间：丢掉半个代理对后是 38 个 char。
        assertEquals(38, result.output().length(), "截断必须封顶在 char 预算内且不留半个代理对");
        assertFalse(result.output().contains("\uFFFD"), "截断点切断多字节字符");
        assertFalse(hasUnpairedSurrogate(result.output()), "截断点切断代理对");
        assertTrue(result.output().startsWith("中文😀"), result.output());
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) return true;
                index++;
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
    }

    private static WorkspaceCommands.CommandResult run(ShellType shell, String script) throws Exception {
        return ProcessRunner.run(shell.buildCommand(script), null, StandardCharsets.UTF_8, 60, 8192);
    }

    /** Availability is decided by a real launch; the sandbox launcher does the same and refuses to substitute. */
    private static boolean available(ShellType shell) {
        try {
            return ProcessRunner.run(shell.buildCommand("exit 0"), null, StandardCharsets.UTF_8, 30, 1024)
                    .exitCode() == 0;
        } catch (Exception missing) {
            return false;
        }
    }
}
