# MXC workspace adapter

The adapter selects exactly one host-native backend: **Windows BaseContainer** or **Linux Bubblewrap**. Windows does not dispatch to a Linux environment. Missing platform capabilities fail closed; there is no local execution or DACL mutation fallback.

## Deployment

Use Java 21, host-native Node >=24 and `@microsoft/mxc-sdk@1.0.0`. Install the SDK with the supplied `runtime/package.json` and lockfile on the target OS. SDK native assets and Node are external deployment resources; only the adapter's scripts are embedded in the JAR.

Windows requires a supported Windows 11 release and an exact request probe reporting `base-container` with `needsDaclAugmentation=false`. The launcher also sets `fallback.allowDaclMutation=false` on the actual request. Linux keeps its existing Bubblewrap, Bash and unprivileged namespace requirements.

```java
MxcRuntime runtime = MxcRuntime.nativeHost(
        Path.of("D:/tools/node/node.exe"),
        Path.of("D:/tools/mxc/node_modules/@microsoft/mxc-sdk"),
        Path.of("D:/application-state/mxc-helpers"));
MxcWorkspaceProvider provider = new MxcWorkspaceProvider(runtime);
```

On Linux, supply the corresponding POSIX paths to the same API. Startup and file budgets default to 30 seconds; use the full constructor to override them. Keep helper/control resources outside all writable workspace grants.

```java
MxcWorkspaceSpec spec = new MxcWorkspaceSpec(
        "D:/projects/example", "tenant-id",
        List.of("D:/tools/build"), List.of("D:/build-cache"),
        Map.of("JAVA_HOME", "D:/tools/jdk"));
```

Set the sixth argument, `writable`, to false for a read-only project. Extra write grants are independent, so omit them for a fully read-only policy. Register `new NamedType(MxcWorkspaceSpec.class, "mxc")` with the execution JSON mapper.

## Execution contract

- Paths are absolute native host paths. Windows accepts drive paths and rejects network or subsystem UNC paths. No drive mapping is performed.
- Windows reports `OsType.WINDOWS`, `ShellType.CMD` and UTF-8 command output. A sandbox-local bootstrap sets code page 65001 before starting CMD, so CMD built-ins and UTF-8 tools share the decoder. Linux continues to report Bash and UTF-8. File content uses its explicit requested charset, independently of terminal output.
- Windows Node commands default to `--preserve-symlinks-main --preserve-symlinks` through `NODE_OPTIONS`. File helpers use fixed arguments/environment and ignore command preloads.
- Network ingress and host loopback remain denied. Egress is denied by default; `MxcSecurityPolicy.allowOutboundNetwork` explicitly permits ordinary outbound access. This is not an unrestricted-network claim. Domain proxying and PTY are not exposed. Persistent stdio is available only through the explicit process-launch capability.
- Read-only subdirectories, junctions/symlinks and outside file accesses are enforced by the backend. File mutations additionally check their canonical targets against explicit grants.
- Explicit whole-drive grants and writable grants overlapping runtime/control resources are rejected. The separate Windows-only `ambientRoots` policy compiles volume readonly grants and writable ordinary directory subtrees while excluding runtime/readonly trees. Flat files at volume roots and structural ancestors remain readonly, avoiding locked profile/temp-file grants. Reparse-point entries are not separately granted writable access.
- Linux may provide private writable temporary layers at structural ancestors. Such shell writes do not change the corresponding host path; the file API rejects them.
- Windows uses UI compatibility mode, with clipboard and input injection denied. This does not provide Win32k syscall lockdown.
- `resolve` performs an actual bound-workspace probe. For advisory service health, use `MxcPlatformProbe.check(runtime)` instead: it validates Node/SDK loading and native capability discovery without extracting helpers, creating a workspace or launching a container.
- Operations are independent, with no resident worker, workspace cache or external environment lifecycle manager. Windows uses the packaged native executor with an exact JSON policy; Linux retains the SDK streaming path.
- Windows timeouts terminate the executor tree; native job closure contains descendants. Java interruption also terminates its process tree. Linux cancellation retains PID/start-identity verification. Output is bounded while streams continue draining. Files are limited to 16 MiB; command output to 4 Mi characters.

## Tool compatibility

The advisory probe requires BaseContainer without DACL augmentation on Windows and a discovered Bubblewrap backend on Linux. It does not test target permissions, command launch, namespaces or tool compatibility. Linux discovery warnings about optional proxy support are reported as diagnostics and do not fail a deny-all deployment. Callers must not treat this health result as an authorization decision; normal operation guards remain mandatory.

CMD works with a narrow project grant. Windows PowerShell directory switching and Maven canonical path discovery may require ancestor enumeration unavailable on older supported builds. The default workspace-only policy does not grant an entire drive to accommodate them. Explicit `ambientRoots` mode permits volume metadata and ordinary directories, and is independently tested with CMD `dir`. See the [platform validation and compatibility report](../docs/mxc-native-platform-validation.md).

## Tests

Enable the host-specific suite with `mxc.node`, `mxc.sdk` and `mxc.test.root`. The Windows suite uses native Windows paths; the Linux suite uses POSIX paths. Each creates only dedicated fixture directories.

```powershell
mvn -B -ntp -pl harness-sandbox-mxc -am test `
  '-Dmxc.node=D:/tools/node/node.exe' `
  '-Dmxc.sdk=D:/tools/mxc/node_modules/@microsoft/mxc-sdk' `
  '-Dmxc.test.root=D:/MxcValidation'
```

Linux uses the same properties with native Linux Java. A host TCP listener enables the extra Linux network control, and separately staged build sources/caches enable `MxcRealBuildTest`.

## MCP stdio

Request MCP sessions receive the resolved workspace. Isolated requests accept only stdio servers;
HTTP/SSE configuration is rejected before any connection starts. An isolated bridge without
`WorkspaceStdioLauncher` is rejected, with no local-process fallback. Existing local MCP behavior
is unchanged.

MXC wraps the existing LangChain4j stdio transport with a persistent session using the same file
and network policy as workspace commands. Provision MCP executables and dependencies
in explicitly readable locations in advance; this adapter does not install packages or widen grants.
Executable lookup happens inside the sandbox. Protocol stdout remains byte-preserving and separate
from stderr; stderr drains continuously with at most 64 KiB forwarded per session. Closing the
request scope cancels the process tree and waits for supervisor cleanup. Suspension retains the scope.

Windows `.cmd`/`.bat` entrypoints use CMD with delayed expansion disabled. Arguments containing
quotes, percent signs or newlines are rejected because batch argument expansion cannot preserve
them reliably; use an executable or Node script entrypoint for arbitrary argv.

`MxcMcpIntegrationTest` uses the same three native test properties above, on either OS. It covers
real MCP initialization/discovery/calls, Unicode and argv, protocol/diagnostic separation, file and
loopback boundaries, initialization failure and confirmed child cleanup.

## Native privacy policy

Use the seventh `MxcWorkspaceSpec` argument for `new MxcSecurityPolicy(deniedPaths, ambientRoots, allowOutboundNetwork)`.
The application owns privacy presets and must supply absolute denied paths. Native `deniedPaths` block content access and mutations, not independently selected read/write/delete operations. They do not guarantee that a directory's existence or metadata is hidden. Missing paths are retained so later-created private files remain denied. Windows requires the exact request probe to report native denied-path support; there is no tool-only or ACL-mutation fallback.

On the validated Windows BaseContainer host, a denied directory's name and attributes remain visible from its accessible parent. Enumerating its children, reading files and writing files fail with permission errors. CMD `dir <denied-directory>` can succeed by displaying the directory entry in its parent and reporting zero files; this does not mean the denied directory is empty. Check the output's directory heading and use an explicit child enumeration such as `dir <denied-directory>\\*` to distinguish these cases. Tests with both `.aws` and an ordinary directory name produced the same behavior, directly through the native executor and through the adapter.

The Windows launcher explicitly sets `HOME`, `USERPROFILE`, `LOCALAPPDATA`, `TEMP` and `TMP` to the workspace by default. This changes process configuration discovery and cache locations; it does not remap absolute filesystem paths or define the host user's profile. Applications must expand privacy presets against the host profile before constructing `deniedPaths`, independently of the sandbox environment. Command and stdio environment overrides do not remove the native absolute-path policy.

`ambientRoots` accepts explicit Windows volume roots. It allows ordinary directory subtrees beyond the current project while keeping deployment-owned runtime/control resources protected. It is unsupported on Linux and fails closed there; existing workspace-only Linux policies remain available. Writable ambient roots still intersect normal host ACLs. Volume-root and runtime-ancestor flat files stay readonly. This is a native policy compiled for each operation, not a claim that every host file is writable.

File/network policy is immutable for a resolved workspace. Application changes require a new resolution. `allowOutboundNetwork` leaves ingress and host loopback denied: the tested Windows host rejects `hostLoopback: allow` before creating a container. Applications must expose this limitation. A host-pooled MCP connection is outside this filesystem boundary; only a workspace-owned stdio session receives the native policy.
