# MXC 第一版实现与验证

> Historical snapshot. Current platform routing is Windows BaseContainer and Linux Bubblewrap. See [current validation](mxc-native-platform-validation.md); the Windows-to-Linux transport described below has been removed.

## 实现范围

新增可选模块 `harness-sandbox-mxc`，通过已有 `WorkspaceProvider` / `WorkspaceBridge` 接入，不修改 core 的 SPI 或引入框架工作区资源缓存。支持 Linux Bubblewrap 和 Windows Java 调用指定 WSL 发行版；六个 bridge 操作均经过 MXC。网络固定全部拒绝，环境变量显式传递，后端不满足要求时直接失败。

Node ≥24、SDK 1.0.0 及原生依赖由部署提供；仓库附带固定版本 npm 清单与锁文件。两个自有 helper 随 JAR 发布并校验解压内容。文件操作使用固定环境，防止用户命令的 `NODE_OPTIONS` 等变量改写文件协议。

配置、路径语义、预算和限制见[模块中文文档](../harness-sandbox-mxc/README.md)。本轮没有迁移 LingXi 业务 provider，没有推送 Git 或发布 Maven Central，Maven 版本保持 `1.1.6`。

## 验证环境

Windows 宿主 Java 21；WSL2 Ubuntu 24.04、OpenJDK 21、Maven 3.9.16、Linux Node 24.20.0、Bubblewrap 0.9、MXC SDK 1.0.0。实测运行依赖版本与新锁文件一致：koffi 3.3.3、node-pty 1.1.0、semver 7.8.5。

“Linux Java”测试同样运行在 WSL2 Linux 内，未验证物理 Linux 主机；“Windows Java → WSL”是实际跨系统调用，并非在 Linux 中模拟 Windows 路径。

## 最终验收结果

| 验证路径 | 结果 |
| --- | --- |
| Windows Java 全模块 `verify`，显式启用 MXC 后端和真实构建测试 | **367 项：366 通过、1 跳过，0 失败／错误**；7 个模块全部成功 |
| 上述流程中的 MXC 模块 | **16/16 通过**：4 个配置／路径／快照测试、1 个 helper 完整性测试、10 个实际隔离测试、1 个真实构建测试 |
| Linux Java 直接调用 MXC | **15/15 通过**，包括相同 10 个隔离测试；本阶段未重复真实构建测试 |
| Windows Java provider 驱动沙箱内独立源码快照 `clean verify` | **成功，约 31.7 秒**；357 项中 356 通过、1 跳过 |
| 源码一致性与产物 | 321 个源码／构建／运行时配置文件在开发目录、实际构建快照、Linux 专项测试快照中 SHA-256 一致；JAR 中两个 helper 与源码一致 |
| 任务结束检查 | 无残留适配器进程；未产生超时／取消测试设置的延迟写入文件 |

沙箱内构建使用独立的源码、用户目录和临时目录，离线读取先前准备的工具与 Maven 缓存。执行真实 `clean verify` 时未跳过普通测试；仅关闭 GPG 和 Javadoc。嵌套构建没有配置再次启动沙箱的测试参数，所以不能将其中 357 项视为又一次完整隔离专项测试；相关后端专项已在外层单独启用并完成。

真实构建快照位于 Linux `java-bridge-build-v3/harness`；同一生产代码的 Linux 专项快照位于 `native-adapter-source-v2/harness`。它们均在 `/home/summit/mxc-validation-20261015/` 下，未对开发仓库运行 `clean`。

## 实际覆盖的隔离行为

- 文件存在性、创建目录、删除、读取、写入和行解析；中文、特殊路径、UTF-16、非法 UTF-8、不可编码字符、缺失文件与超大文件错误。
- 宿主未授权文件读写拒绝；只读目录和可写工作区内的嵌套只读目录不可修改；符号链接和父目录穿越无法读取／修改宿主 canary。
- 完整 Bash 脚本、引号、变量、管道、换行、退出码、stdout/stderr 和输出预算；只传递显式环境，文件 helper 不执行命令配置的预加载代码。
- 执行超时和 Java 线程中断后，子孙进程的延迟写入没有发生；Windows 到 WSL 的取消路径实际执行了 Linux 侧清理。
- Linux 宿主回环 TCP 监听在沙箱连接前后均可从无沙箱进程连接，沙箱内连接被阻断。不是单纯连接一个未监听端口。
- SDK 不存在、整根文件系统授权、覆盖控制目录的可写授权以及非显式 MXC spec 均拒绝；helper 被修改时拒绝复用且不覆盖被修改文件。
- 每次 provider 解析实际验证工作区读写、helper 只读、控制目录隐藏，以及 mount／PID／network 命名空间分离。

## 第一版端到端开销

下表为本机各 5 次调用的观测值，系统缓存未清空；包含 Java、Node 加载、SDK 和实际沙箱操作。WSL 样本采集时有其他验收任务，不能当作冷启动或稳定分位数。

| 调用路径 | provider 解析（含隔离探测） | 简单命令 | 写一个小文件 | 读一个小文件 |
| --- | --- | --- | --- | --- |
| Windows Java → WSL | 291–1161 ms，中位 301 ms | 261–273 ms | 260–275 ms | 263–283 ms |
| Linux Java | 169–342 ms，中位 171 ms | 168–180 ms | 169–176 ms | 168–172 ms |

此前原生 SDK 的毫秒级启动测量不代表这一版 Java 接口的延迟。第一版每次操作启动独立 Node，并使用流式 SDK，避免无界收集输出，也没有常驻 worker。小文件密集调用仍有明显成本，后续需要依据实际工具调用量决定是否优化传输；本轮没有引入隐式常驻进程或生命周期缓存。

## 尚未承诺的能力

原生 Windows ProcessContainer、域名代理、允许联网路径、DNS／IPv6 专项、PTY、交互 stdin、严格 deny 目录语义及并发取消专项不在第一版支持范围。Linux 基础系统只读挂载和私有临时目录仍存在，不能宣称只有工作区可读。生产业务集成、打包分发验证和后续依赖升级仍需独立验收。

## 本地证据位置

完整日志与辅助脚本位于 Git 忽略目录 `target/mxc-validation/`：

- `mxc-v1-acceptance.log`：Windows 全量验收，含 Java bridge 驱动真实构建。
- `mxc-v1-native-acceptance.log`：Linux Java 专项验收。
- `mxc-v1-java-wsl-benchmark.jsonl`、`mxc-v1-java-linux-benchmark.jsonl`：逐次端到端耗时。
- `mxc-v1-source-hashes.json`：构建快照源码清单。
- `mxc-v1-final-audit.py`：源码一致性、JAR helper、残留进程与延迟写入检查。

该目录不是发布内容，仓库根目录的 Maven `clean` 会删除它。常规接入与重跑方式保留在模块 README 中。
