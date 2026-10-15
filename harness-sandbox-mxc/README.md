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
- Windows reports `OsType.WINDOWS`, `ShellType.CMD` and the Windows OEM output charset. Linux continues to report Bash and UTF-8. File content uses its explicit requested charset, independently of terminal output.
- Windows Node commands default to `--preserve-symlinks-main --preserve-symlinks` through `NODE_OPTIONS`. File helpers use fixed arguments/environment and ignore command preloads.
- Network ingress, egress and host loopback are denied. Domain proxying, PTY and interactive stdin are not exposed.
- Read-only subdirectories, junctions/symlinks and outside file accesses are enforced by the backend. File mutations additionally check their canonical targets against explicit grants.
- Whole-drive/root grants and writable grants overlapping runtime/control resources are rejected.
- Linux may provide private writable temporary layers at structural ancestors. Such shell writes do not change the corresponding host path; the file API rejects them.
- Windows uses UI compatibility mode, with clipboard and input injection denied. This does not provide Win32k syscall lockdown.
- `resolve` performs an actual bound-workspace probe. An application service status check can use its own disposable workspace; it need not probe user projects.
- Operations are independent, with no resident worker, workspace cache or external environment lifecycle manager. Windows uses the packaged native executor with an exact JSON policy; Linux retains the SDK streaming path.
- Windows timeouts terminate the executor tree; native job closure contains descendants. Java interruption also terminates its process tree. Linux cancellation retains PID/start-identity verification. Output is bounded while streams continue draining. Files are limited to 16 MiB; command output to 4 Mi characters.

## Tool compatibility

CMD works with a narrow project grant. Windows PowerShell directory switching and Maven canonical path discovery may require ancestor enumeration unavailable on older supported builds. The adapter does not grant an entire drive to accommodate them. See the [platform validation and compatibility report](../docs/mxc-native-platform-validation.md).

## Tests

Enable the host-specific suite with `mxc.node`, `mxc.sdk` and `mxc.test.root`. The Windows suite uses native Windows paths; the Linux suite uses POSIX paths. Each creates only dedicated fixture directories.

```powershell
mvn -B -ntp -pl harness-sandbox-mxc -am test `
  '-Dmxc.node=D:/tools/node/node.exe' `
  '-Dmxc.sdk=D:/tools/mxc/node_modules/@microsoft/mxc-sdk' `
  '-Dmxc.test.root=D:/MxcValidation'
```

Linux uses the same properties with native Linux Java. A host TCP listener enables the extra Linux network control, and separately staged build sources/caches enable `MxcRealBuildTest`.