# MXC 工作区适配器（第一版）

`harness-sandbox-mxc` 为现有 `WorkspaceProvider` / `WorkspaceBridge` 提供 Microsoft MXC 适配。第一版支持 **Linux Bubblewrap**，以及 **Windows Java → 指定 WSL 发行版 → Bubblewrap**。命令执行和五个文件操作使用相同策略，网络固定全部拒绝；不会退回宿主 `local` 执行。

这是本地开发版本，尚未发布 Maven Central。原生 Windows ProcessContainer、macOS、域名网络代理、PTY、交互 stdin 和严格 deny 目录规则未提供入口。

## 部署依赖

- Java 21。
- Linux 环境内的 Node.js 24 或以上、`bubblewrap`、`bash`、`env`、`setsid`；允许使用非特权用户命名空间。
- 固定版本 `@microsoft/mxc-sdk` **1.0.0**。安装位置由应用提供，不在运行时下载或升级。
- Windows 使用明确指定的 WSL 发行版。Node 和 SDK 都必须安装在该 Linux 发行版内，不能复用 Windows 的 `node.exe` 或原生 npm 依赖。

仓库 `runtime/package.json` 与锁文件用于准备独立的部署目录：将它们复制到部署目录后，在目标 Linux 环境内运行 `npm ci`。SDK 及原生依赖作为外部部署文件提供，**没有打进 Java JAR**。两个自有 `.mjs` helper 随 JAR 发布，首次使用时按内容 SHA-256 解压到应用指定目录，复用前校验内容，发现修改即报错。

部署应使 SDK、Node 和 helper 目录处于工作区写授权之外。不要把沙箱初始化器注册为自动安装系统组件的入口。

## 注册 provider

添加可选模块依赖（当前需从本地源码构建）：

```xml
<dependency>
    <groupId>io.github.yesnonononononi</groupId>
    <artifactId>harness-sandbox-mxc</artifactId>
    <version>1.1.6</version>
</dependency>
```

Linux Java：

```java
MxcRuntime runtime = MxcRuntime.linux(
        Path.of("/opt/lingxi/node/bin/node"),
        Path.of("/opt/lingxi/mxc/node_modules/@microsoft/mxc-sdk"),
        Path.of("/var/lib/lingxi/mxc-helpers"));
MxcWorkspaceProvider provider = new MxcWorkspaceProvider(runtime);
```

Windows Java 调用 WSL：

```java
MxcRuntime runtime = MxcRuntime.wsl(
        "Ubuntu",
        "/opt/lingxi/node/bin/node",
        "/opt/lingxi/mxc/node_modules/@microsoft/mxc-sdk",
        Path.of("D:/app-state/mxc-helpers"));
MxcWorkspaceProvider provider = new MxcWorkspaceProvider(runtime);
```

将 `provider` 作为 `WorkspaceProvider` bean 注册到应用。框架已有 resolver 会按 `type() == "mxc"` 路由。资源准备和目录生命周期由部署管理，本模块不注册工作区缓存或清理服务。

默认启动与文件操作预算均为 30 秒。需要调整时使用 `MxcRuntime` 的完整构造器，传入两个正数 `Duration`；单条命令的预算来自 `WorkspaceBridge.execute`。外层传输另留启动和清理时间，因此执行超时的总返回耗时可能超过命令预算。

## 配置请求

```java
MxcWorkspaceSpec spec = new MxcWorkspaceSpec(
        "/home/agent/projects/demo",      // 必须已存在，自动授予读写
        "tenant-123",                    // 多租户部署显式提供 scope
        List.of("/opt/project-tools"),   // 额外只读路径，必须已存在
        List.of("/home/agent/cache"),    // 额外读写路径，必须已存在
        Map.of("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64",
                "PATH", "/usr/lib/jvm/java-21-openjdk-amd64/bin:/usr/bin:/bin"));

AgentRequest request = AgentRequest.builder()
        .messages(List.of(UserMessageEntity.from("检查项目并执行离线测试")))
        .workspaceSpec(spec)
        .build();
```

第一版要求显式 `MxcWorkspaceSpec`，不读取任意 JSON 策略或 `BasicWorkspaceSpec.configuration` 中的隐藏开关。环境默认只有 `PATH`、`HOME`、`TMPDIR`、`LC_ALL`，再叠加显式配置，并设置 `HARNESS_WORKSPACE`。宿主环境不会自动传入 helper 或沙箱。

显式环境配置仅用于用户命令；文件 helper 和启动探测使用固定的最小环境，避免 `NODE_OPTIONS` 等预加载变量改变文件操作协议。

若持久化并恢复执行快照，在应用的 JSON mapper 中注册稳定类型名：

```java
ObjectMapper mapper = ExecutionJson.newObjectMapper(
        new NamedType(MxcWorkspaceSpec.class, "mxc"));
```

## 路径与隔离语义

- WSL spec 可使用 POSIX 绝对路径、`D:/projects/demo` 或所选发行版的 `\\wsl.localhost\Ubuntu\...` / `\\wsl$\Ubuntu\...`。盘符映射按 `/mnt/<盘符>`，自定义挂载请直接传 POSIX 路径；其他网络共享和其他发行版的 UNC 路径会拒绝。
- `Workspace.workDir()` 报告 **Linux 路径**，模型生成的命令按 Bash 语法执行。`Workspace.resolve()` 在 Windows 返回对应 WSL UNC `Path`，供框架几何路径判断和 bridge 转换；所有实际读写必须经过 bridge，不能用宿主 `Files` 绕过隔离。
- 命令是完整脚本文本，保留引号、空白、管道和换行。工作目录可空，默认使用绑定工作区；非空目录需为可映射的绝对路径。
- 工作目录默认可写，额外授权不会自动扩展为整个磁盘。部署运行时、控制文件和基础系统路径不允许被可写授权覆盖。拒绝整根 `/` 授权，以及“只读父目录内再授予可写子目录”的配置。只读子目录可以覆盖可写工作目录，需由实际后端实施。
- 符号链接由隔离挂载和 Linux 文件系统实施边界。`encloses` 仍是几何判断，不能独立证明符号链接目标获准访问。授权根路径会在 Linux 侧规范化，别名未挂载时可能不可用。
- Bubblewrap 仍提供基础系统只读路径、私有临时目录和 `/proc` 等运行环境；不能把它理解为“只能读取工作区”。本版不开放 `deniedPaths`，避免将可写临时遮罩解释成严格拒绝写入。

`resolve()` 每次都会实际启动沙箱：验证工作区读写、helper 只读、控制目录隐藏，以及 mount / PID / network 命名空间隔离。失败会抛异常。文件 `exists` 仅将文件系统真实的 `ENOENT` / `ENOTDIR` 解释为不存在，传输或后端失败会抛出 `UncheckedIOException`。

## 执行、输出和取消

每次操作启动独立 Node helper，无常驻 worker。Node 使用 MXC 的流式 API，收集到上限后继续排空输出。命令合并 stdout / stderr，独立流之间按到达顺序拼接；文件读写使用 Base64 传输和严格字符编解码。单文件限制 16 MiB，单条命令输出预算最多 4 Mi 个 Java 字符，超出预算会截断并标记 `truncated`。

MXC 处理命令预算；Java `ProcessRunner` 另设外层预算。主动中断在 Windows 侧终止传输后，还会向 Linux 控制端发送取消，校验 PID 启动身份后终止对应进程及会话。正常操作清理控制文件；异常且尚未注册 PID 时可能保留极小的取消标记，防止延迟启动的请求继续执行。部署可在确认无在途任务后清理其自有 helper 目录。

## 运行测试

默认 `mvn test` 运行单元测试，后端测试需要显式启用。以下从 Windows PowerShell 调用已部署的 WSL SDK：

```powershell
mvn -B -ntp -pl harness-sandbox-mxc -am test `
  '-Dtest=MxcRuntimeTest,MxcHelpersTest,MxcWorkspaceIntegrationTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' '-Dgpg.skip=true' `
  '-Dmxc.wsl.distribution=Ubuntu' `
  '-Dmxc.node=/opt/lingxi/node/bin/node' `
  '-Dmxc.sdk=/opt/lingxi/mxc/node_modules/@microsoft/mxc-sdk' `
  '-Dmxc.test.root=/home/agent/mxc-validation'
```

Linux Java 使用同样参数，省略 `mxc.wsl.distribution`。测试会在指定测试根目录创建独立案例目录，不删除用户目录。网络测试另需 `mxc.test.host.port` 指向已启动的 Linux 宿主回环 TCP 监听；它会在沙箱连接前后各执行一次无沙箱连接对照。

`MxcRealBuildTest` 还需要提前准备独立构建快照及工具／缓存目录，配置 `mxc.test.build.root`、`mxc.test.build.dependencies`。它通过公开 Java provider 执行离线全模块 `clean verify`，不应指向正在开发的仓库根目录。日志与本次证据见[第一版验证报告](../docs/mxc-v1-validation.zh-CN.md)。
