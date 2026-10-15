# SRT 清理与 MXC 接入准备

## 当前状态

本次清理移除基于 Anthropic sandbox-runtime 的 `harness-local-sandbox` 模块、SRT 初始化与安装入口、策略转换、Node helper 及对应测试，并移除根 POM 的模块与依赖管理声明。清理前完整检查点为 Git 提交 `09fc279`，其中包括既有模型配置修改，可按提交恢复。

本次仅准备新实现的接入边界，尚未提供生产可用的 MXC provider。Maven 版本保持 `1.1.6`，未发布 Maven Central；后续发布新实现时再确定版本。

清理前在 Windows 宿主执行全量 `mvn test -Dgpg.skip=true` 成功，397 项中 393 通过、4 跳过。清理后执行 `mvn verify -Dgpg.skip=true -Dmaven.javadoc.skip=true` 成功，剩余 6 个模块的测试与打包均完成，351 项中 350 通过、1 跳过。减少的 46 项属于移除的 SRT 模块；签名和 Javadoc 不在本轮验证范围内。日志分别为 `target/mxc-validation/pre-cleanup-framework-tests.log` 与 `target/mxc-validation/post-cleanup-framework-verify.log`。

原先依赖 `harness-local-sandbox`、`com.summit.sandbox` 或注册 `SandboxWorkspaceProvider` 的应用需要迁移，不能直接用本次源码替换旧依赖。指定未安装的 provider 会报错；应用应继续显式指定隔离 provider，避免省略 `workspaceSpec` 后使用默认的宿主本地工作区。

## 保留的框架边界

- `WorkspaceProvider` / `WorkspaceSpec` / `WorkspaceResolver`：按请求选择环境。资源创建、复用、恢复、持久化和清理由 provider 所属部署管理，框架不引入工作区资源缓存或销毁器。
- `Workspace` / `WorkspaceBridge`：保留环境描述、路径语义，以及 `exists`、`createDirectories`、`deleteFile`、`readString`、`writeString`、`execute` 六个操作。
- `RuntimeEnvironment` / `ShellType` / `ProcessRunner`：保留 shell 描述、部署提供的可执行文件路径、并行处理输入输出、输出截断、超时与进程树终止。
- 默认 `local` provider：继续表示宿主文件系统和进程执行，不提供操作系统沙箱隔离。隔离 provider 不可用或策略不可满足时必须失败，不能转交 `local`。

## 新实现的约束

计划以独立可选模块适配 Microsoft MXC，通过现有 SPI 接入，避免 SDK 或后端类型进入 `harness-core`。初始验证基线为 `@microsoft/mxc-sdk` 1.0.0；原生执行器、Node 与 helper 的版本和位置由部署显式提供并校验。

1. 文件操作和命令执行必须使用相同隔离策略。文件 API 不能直接读取宿主文件后将结果包装成沙箱结果；`exists` 的执行失败不能变成“不存在”。
2. 原始脚本文本通过结构化请求传递，保留引号、空格、变量、管道和换行。文本编解码失败应显式报错，不能静默替换字符。
3. Linux 路径与 Windows 路径的转换在适配层完成，覆盖工作目录、只读工具链、允许写入目录、拒绝目录、符号链接与父目录穿越。Windows Java 调用 WSL 的桥接仍需实现和测试，不能将 Java `Path` 的宿主语义当作 Linux 路径语义。
4. 启动探测须执行实际沙箱命令并验证策略能力。SDK 支持标志或探测进程退出码不足以证明使用了预期隔离层。后端缺失、策略字段不受支持或能力降级必须明确失败。
5. 环境变量按允许列表传递；读写授权不得为了兼容工具链扩大到整个磁盘。不得隐式修改宿主 DACL 或自动提升权限安装组件。
6. 使用独立的启动、执行与取消预算；验证超时后子孙进程终止、输出资源回收和临时请求清理。PTY、交互输入和并发取消需要额外验收。
7. 网络策略须区分全部拒绝、域名代理及必要的开发服务访问。域名代理、DNS、IPv6 和允许联网路径尚未完成验证，不能直接宣称与 SRT 功能等价。

## 已取得的运行证据

以下来自本机 MXC 1.0.0 / WSL2 Ubuntu 24.04 的独立测试，不等同于尚未实现的 Java provider 验收，也不覆盖物理 Linux 主机。

| 验证项 | 结果与边界 |
| --- | --- |
| 基本 Linux 隔离，ext4 与 `/mnt/d` | 两种布局各 38 项中 37 项符合严格预期；目录拒绝规则用可写临时层遮罩，宿主原目录保持隐藏且未修改，但不满足“目录内任何写入都必须失败”的语义 |
| Windows 原生基础隔离 | 更新至 26200.9278 后 29/29 通过；未解决受限授权下 Maven 的路径规范化问题 |
| harness 真实构建 | 全模块 `clean install` 成功，397 项：392 通过、5 跳过、0 失败；约 39 秒 |
| LingXi 后端 | `clean verify` 执行 775 项，13 失败、15 跳过；同环境去掉沙箱后复现相同 13 个失败，不能称为测试通过 |
| LingXi 前端 | TypeScript/Vite 构建成功；测试 362 项中 1 项失败，同环境无沙箱对照复现相同失败 |
| 带缓存和依赖的大目录启动 | 验证目录约 3.42 GiB、55,822 个文件；5 次新执行器进程测量，启动至 shell 标记约 7–27 ms，总调用约 11–30 ms；包含 Node/SDK 预热，未清理系统缓存，不代表冷启动分位数 |

真实构建使用快照 `20261015T083840-1ffea665`，在沙箱外准备依赖，再以独立缓存和全部拒绝网络策略执行构建。没有对真实项目配置整个磁盘可读。Linux 后端仍有基础系统只读挂载，不能声称只可读取工作区。

Windows 原生 Maven 的问题涉及受限父目录权限下的路径规范化；SDK 1.0.0 的公开请求类型还不能透传 `fallback.allowDaclMutation`。Linux/WSL 是当前优先接入路径，但 Windows 宿主到 WSL 的完整桥接尚未完成。原生 Windows 路径与 SDK 能力不足时应拒绝启用对应能力。

详细日志、隔离脚本与完整中文报告保留在本地忽略目录 `target/mxc-validation/`，包括 `maven-linux-assessment.zh-CN.md` 与 `real-build-validation.zh-CN.md`。它们不是发布包内容；运行仓库根目录的 Maven `clean` 会删除该目录。

## 接入验收顺序

先实现独立 MXC 模块与部署探测，再对六个 bridge 操作、Windows/WSL 路径映射、拒绝规则、符号链接、环境和进程隔离进行正反向测试；随后重跑中等规模工作树的初始化与真实 Maven/Node 构建对照。域名网络、PTY 和并发取消在完成专项验证前保持未支持状态。最后才调整业务应用的 provider 注册与依赖版本。
