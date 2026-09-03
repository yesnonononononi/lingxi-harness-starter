package com.summit.tools.terminal;

import com.summit.core.runtime.ShellType;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 命令执行前的最后一道安全防线（危险命令黑名单）。
 *
 * <p>定位与边界：
 * <ul>
 *   <li>只负责拦截「执行后基本不可逆 / 高危」的破坏性命令（格式化磁盘、递归删除系统路径、
 *       停机重启、远程下载并执行、fork bomb 等），防止 LLM 被注入或误生成灾难性指令；</li>
 *   <li>它不是唯一安全边界。黑名单永远无法穷举，完整的执行安全应叠加
 *       {@code CommandConfirmLevel}（PRE_EXEC_CONFIRM / DANGEROUS_BLOCK）人工确认机制、
 *       最小权限运行与工作区沙箱；</li>
 *   <li>该守卫仅做文本级检测，无法防御依赖工作目录的组合手法（如 {@code cd / && rm -rf *}），
 *       此类行为必须靠沙箱与工作目录隔离来兜底。</li>
 * </ul>
 *
 * <p>相对旧版的修复要点：
 * <ol>
 *   <li>旧版模式锚定行首（^）再配合 {@code find()} 匹配，导致危险命令前拼接任意前缀即可绕过，
 *       例如 {@code sudo rm -rf /}、{@code cd /tmp && rm -rf /}。新版不锚定行首，
 *       危险命令出现在命令串任意位置都会命中；</li>
 *   <li>Windows 命令不区分大小写，统一开启 {@code CASE_INSENSITIVE}，否则 {@code FORMAT C:}、
 *       {@code Remove-Item C:\Windows} 之类可绕过；</li>
 *   <li>按 shell 类型（Windows / Unix）分发两套危险面，降低跨平台误报；</li>
 *   <li>递归删除类采用「命令 + 递归标志 + 危险目标路径」组合判定，并按 {@code &&/;/换行}
 *       切分语句，避免跨语句拼凑造成误报。</li>
 * </ol>
 */
public final class CommandGuard {

    private CommandGuard() {
    }

    // =====================================================================
    // Linux / Unix (bash / zsh / sh)
    // =====================================================================

    /** 直接命中即拦截的 Unix 高危命令。 */
    private static final List<Pattern> UNIX_HARD_BLOCK = List.of(
            // 文件系统创建 / 擦除工具
            Pattern.compile("(?i)\\b(?:mkfs(?:\\.\\w+)?|wipefs|shred)\\b"),
            // dd 向块设备写（容忍 of= 引号、非行首变体，如 "sudo dd if=.. of=/dev/sda"）
            Pattern.compile("(?i)\\bdd\\b[^\\n]*\\bof=\\s*[\"']?/dev/(?:sd|hd|vd|nvme|mmcblk|dm-|md)\\d*"),
            // shell 重定向写块设备，如 "cat x > /dev/sda"（无词边界，设备名后可能跟分区号/字符）
            Pattern.compile("(?i)[>»][^\\n]*/dev/(?:sd|hd|vd|nvme|mmcblk|dm-|md)\\d*"),
            // shell 重定向写关键系统目录（篡改 hosts/授权/启动文件等）
            Pattern.compile("(?i)[>»][^\\n]*/(?:etc|boot|root|proc)/"),
            // 停机 / 重启 / 运行级切换
            Pattern.compile("(?i)\\b(?:shutdown|reboot|halt|poweroff|telinit)\\b"),
            Pattern.compile("(?i)\\binit\\s+[06]\\b"),
            Pattern.compile("(?i)\\bsystemctl\\s+(?:poweroff|reboot|halt|suspend|hibernate)\\b"),
            // fork bomb，如 ":(){ :|:& };:"
            Pattern.compile("(?i):\\s*\\(\\s*\\)\\s*\\{"),
            // 远程下载后管道直接执行，如 "curl -sL ... | sudo bash"
            Pattern.compile("(?i)\\b(?:curl|wget|aria2c|lynx)\\b[^\\n]*\\|[^\\n]*\\b(?:sh|ba?sh|zsh|dash|fish)\\b"),
            // chmod 对绝对路径批量赋权
            Pattern.compile("(?i)\\bchmod\\b[^\\n]*\\b(?:777|0777|a\\+w|a\\+rwx|o\\+w)\\b[^\\n]*\\s[\"']?/"),
            // chown -R 到绝对路径
            Pattern.compile("(?i)\\bchown\\b[^\\n]*\\s-r\\b[^\\n]*\\s[\"']?/"),
            // find 自根目录（/ 前缀绝对路径）起批量删除
            Pattern.compile("(?i)\\bfind\\b[^\\n]*?\\s+[\"']?/(?:[^\\s\"']*/?)*[^\\n]*?\\s+-delete\\b")
    );

    /** rm 递归删除组合判定：命令本体。 */
    private static final Pattern UNIX_RM_CMD = Pattern.compile("(?i)\\brm\\b");
    /** rm 递归删除组合判定：递归标志（-r / -rf / -fr / -R / --recursive，参数可拆分）。 */
    private static final Pattern UNIX_RM_FLAG = Pattern.compile("(?i)(?:^|\\s)-(?:\\S*[rR]\\S*)\\b|\\s--recursive\\b");
    /** rm 递归删除组合判定：危险目标 = 以 / 开头的绝对路径、家目录 ~ / $HOME。 */
    private static final Pattern UNIX_RM_TARGET = Pattern.compile(
            "(?i)(?:^|[\\s\"'])(?:/[^\\s\"']*|~(?:/[^\\s\"']*)?|\\$HOME(?:/[^\\s\"']*)?)");

    // =====================================================================
    // Windows (cmd / PowerShell)
    // =====================================================================

    /** 直接命中即拦截的 Windows 高危命令。 */
    private static final List<Pattern> WINDOWS_HARD_BLOCK = List.of(
            // 格式化盘符（cmd format，参数可前后穿插）
            Pattern.compile("(?i)\\bformat\\b[^\\n]*\\b[a-z]:(?:\\s|$)"),
            // PowerShell 磁盘 / 卷破坏性 cmdlet
            Pattern.compile("(?i)\\b(?:Format-Volume|Clear-Disk|Clear-Volume|Initialize-Disk|Remove-PhysicalDisk)\\b"),
            // 向系统目录写文件
            Pattern.compile("(?i)\\b(?:Set-Content|Add-Content|Out-File|Copy-Item|Move-Item|New-Item)\\b[^\\n]*\\b[a-z]:\\\\(?:Windows|Program Files|ProgramData)\\b"),
            // 注册表系统级配置破坏
            Pattern.compile("(?i)\\breg\\s+(?:delete|add|copy|restore|save|load|unload)\\s+(?:HKLM|HKCR|HKU)\\b"),
            // 网络下载内容直接执行 / Invoke-Expression 执行远程内容
            Pattern.compile("(?i)\\b(?:Invoke-Expression|iex)\\b[^\\n]*(?:DownloadString|New-Object|curl|wget|iwr|Invoke-WebRequest|http)"),
            // 停机 / 重启 / 停服务
            Pattern.compile("(?i)\\b(?:shutdown|Restart-Computer|Stop-Computer|Stop-Service)\\b"),
            // 磁盘分区脚本工具（agent 无合法使用场景）
            Pattern.compile("(?i)\\bdiskpart\\b")
    );

    /** 递归删除组合判定：删除类命令（含 PowerShell 别名）。 */
    private static final Pattern WIN_DELETE_CMD = Pattern.compile("(?i)\\b(?:Remove-Item|rm|ri|del|erase|rd|rmdir)\\b");
    /** 递归删除组合判定：递归/强制标志（PS: -r/-Recurse/-Force；cmd: /s /q /f）。 */
    private static final Pattern WIN_DELETE_FLAG = Pattern.compile(
            "(?i)(?:^|\\s)-(?:r\\b|recurse\\b|rec\\b|f\\b|fo\\b|force\\b)|(?:^|\\s)/(?:s|q|f)\\b");
    /** 递归删除组合判定：危险目标 = 系统根目录（含 Users）或盘符根本身（根后可能跟参数）。 */
    private static final Pattern WIN_DELETE_TARGET = Pattern.compile(
            "(?i)\\b[a-z]:\\\\(?:Windows|Program Files|ProgramData|Users)\\b|\\b[a-z]:\\\\[\"']?(?:\\s|$)");

    // =====================================================================
    // 入口
    // =====================================================================

    /**
     * 判断命令是否允许执行（shell 未知时按最严标准：两套规则同时生效）。
     *
     * @return true 表示允许
     */
    public static boolean isAllowed(String commandLine) {
        return isAllowed(commandLine, null);
    }

    /**
     * 按实际 shell 类型判断命令是否允许执行。
     *
     * @param shellType 目标 shell；为 null（未知平台）时拒绝一切，交由调用方报错，绝不放过
     * @return true 表示允许
     */
    public static boolean isAllowed(String commandLine, ShellType shellType) {
        if (commandLine == null || commandLine.isBlank()) {
            return true;
        }
        if (shellType == null) {
            // 未知 shell：宁可误拦不可漏拦
            return !matchesAny(UNIX_HARD_BLOCK, commandLine)
                    && !matchesAny(WINDOWS_HARD_BLOCK, commandLine)
                    && !isUnixRmDelete(commandLine)
                    && !isWindowsDelete(commandLine);
        }
        return switch (shellType) {
            case POWERSHELL, PWSH, CMD ->
                    !matchesAny(WINDOWS_HARD_BLOCK, commandLine) && !isWindowsDelete(commandLine);
            case BASH, ZSH, SH ->
                    !matchesAny(UNIX_HARD_BLOCK, commandLine) && !isUnixRmDelete(commandLine);
        };
    }

    private static boolean matchesAny(List<Pattern> patterns, String commandLine) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(commandLine).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Unix：rm 递归删除绝对路径 / 家目录。
     * 先按 &&、||、;、换行切分语句，避免 "rm a; ls -r /" 之类跨语句拼凑误报；
     * 单竖线管道不切分，保证 "x | rm -rf /" 等仍被识别。
     */
    private static boolean isUnixRmDelete(String commandLine) {
        for (String segment : commandLine.split("(?:&&|\\|\\||;|\\r?\\n)+")) {
            if (UNIX_RM_CMD.matcher(segment).find()
                    && UNIX_RM_FLAG.matcher(segment).find()
                    && UNIX_RM_TARGET.matcher(segment).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Windows：Remove-Item/del/rd 等递归删除系统目录或盘符根。
     */
    private static boolean isWindowsDelete(String commandLine) {
        for (String segment : commandLine.split("(?:&&|\\|\\||;|\\r?\\n)+")) {
            if (WIN_DELETE_CMD.matcher(segment).find()
                    && WIN_DELETE_FLAG.matcher(segment).find()
                    && WIN_DELETE_TARGET.matcher(segment).find()) {
                return true;
            }
        }
        return false;
    }
}
