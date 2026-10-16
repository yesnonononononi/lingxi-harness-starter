# Host-native MXC validation

Windows selects native ProcessContainer and requires BaseContainer. Linux selects Bubblewrap. There is no Windows-to-Linux transport, local execution fallback or DACL mutation fallback. Node >=24 and SDK 1.0.0 are deployed for the host OS.

Service health now uses `MxcPlatformProbe`: a single Node process reads SDK metadata, loads its native library and queries capabilities. No helper extraction, fixture directory, file canary, workspace or container is created. A positive result is advisory; actual launch and operation-time authorization remain unchanged. Linux optional-proxy discovery warnings do not reject a deny-all deployment. The earlier disposable-workspace health result below is historical runtime evidence.

The Windows test host is 25H2, build 26200.9278. Exact request probes confirm BaseContainer, no DACL augmentation and no enumeration-only capability. The actual request also sets `fallback.allowDaclMutation=false`. The packaged native executor is used because the SDK high-level request does not expose this flag.

Official OS support: https://github.com/microsoft/mxc/blob/main/docs/backends/process-container/os-version-support.md

## Validation

- The UTF-8 command correction reproduced `MalformedInputException` and GBK environment metadata with the original launcher. Four new native Windows regressions now cover Node Chinese/emoji output, CMD code page 65001, stdout/stderr, mixed CMD/Node output, quoted arguments, pipes, redirection and partial UTF-8 truncation. Existing exit-code, boundary, timeout and interruption tests also passed. Command preloads are excluded from the bootstrap and retained for the workload; that fixture passed separately after correcting its Node preload path/options. Evidence: `target/mxc-validation/command-encoding-before.log`, `command-encoding-windows-final.log` and `command-encoding-preload-final.log`.
- The accompanying regression passed 69 core tests, 51 adapter tests and seven native MCP tests. MCP cleanup checks require a fresh fixture root: reusing a directory containing earlier interruption-control files fails their global empty-directory assertion. The Linux-only real-build test was skipped on Windows.
- Initial validation passed eight native Windows Java integration tests: CMD/OEM Unicode output and exit codes, file CRUD, terminal/file boundaries, read-only mode, junction/parent traversal, command preload isolation, rejected whole-drive grants, output limits, timeout of a confirmed-started child, interruption, and loopback denial between positive controls.
- Windows full Maven reactor passed. MXC ran 14 tests, 13 passed and the Linux real-build case skipped. The kernel retained its existing conditional skip.
- Linux Java regression passed. MXC ran 18 tests, 16 passed, with real-build and host-listener cases skipped because their parameters were not supplied. The Linux test host was an existing Ubuntu environment; the application adapter has no transport into it.
- Making a compiled launcher request writable caused the intended read-only assertion to fail. Restoring it passed; production source was not mutated.
- An additional UTF-8 Windows OEM regression reproduced Java rejecting `cp65001`; the adapter maps that identifier to `UTF-8`. The five runtime unit tests and helper integrity test passed after the fix. Other OEM code pages retain their native decoders.
- Business directional regression: 21 tests passed, including native integration. Six desktop tests and TypeScript/Vite build passed.
- `POST /sandbox/service/check` returned `ready=true, backend=BaseContainer` using a disposable workspace. The old validation JVM was replaced at localhost port 18088.

Windows commands now initialize code page 65001 inside the sandbox before starting CMD and report UTF-8 to Java. Changing the code page inside an already-running CMD leaves its cached output encoding unchanged; starting the command shell after the change is required. The bootstrap inherits the sandbox's hidden console and passes the original script through JSON, avoiding another shell expansion. File helpers exchange UTF-8 JSON and Base64 independently. External tools that explicitly emit a legacy encoding still need a matching explicit output charset. UI compatibility is enabled for Node; clipboard/input injection remain denied, but Win32k syscall lockdown is not claimed.

## Tool compatibility

Node entry-point canonicalization can inspect the drive root. Preserve-symlink options allow basic Node and helper execution without granting it. These options change symlink/module semantics and do not establish arbitrary npm build compatibility. File helpers ignore command environment preloads.

Windows PowerShell can initialize its location at `C:\` when project ancestors are inaccessible. Explicit `Set-Location -LiteralPath` also failed on this host. The adapter therefore reports and uses CMD; explicit PowerShell invocation does not imply supported relative-path behavior.

Maven 3.9.16 calls `Path.toRealPath()` while initializing CLI metadata. Access denial makes `getCanonicalPath` recurse into parents until it dereferences a null root parent; error reporting then fails because logging is not initialized.

Primary source: https://github.com/apache/maven/blob/maven-3.9.16/maven-embedder/src/main/java/org/apache/maven/cli/MavenCli.java

A scratch-only `CompatibleMavenCli` catches that specific initialization failure and uses normalized absolute CLI metadata. Under the same narrow BaseContainer policy:

| Control | Result |
| --- | --- |
| Original Maven CLI `-version` | Failed with the initialization/reporting exception |
| Experimental CLI `-version` | Passed |
| Experimental CLI, minimal POM, offline `validate` | Passed |
| Outside canary read by the experimental CLI | Denied before both successful runs |

This demonstrates feasibility, not a production patch or a real project build. Installed Maven files were not modified. The experiment remains in ignored validation material.

The preferred durable solution is enumeration-only access to necessary ancestors when the host actually advertises it, followed by canonical-path and negative sibling-read tests. This host does not advertise it; a support-table version alone cannot establish capability. An upstream tool fix can instead handle denied canonicalization safely. A deployment-owned Maven bootstrap is a possible interim integration, but requires `-f`, multi-module, symlink, plugin/extension and real-build regression before adoption. Generic Java interception, drive-root read grants and automatic fallback are not implemented.

## Evidence and limits

Framework `target/mxc-validation` contains `native-platform-install.log`, `native-platform-linux-tests.log`, `native-readonly-mutation.log`, `native-readonly-restored.log`, and `native-maven-compat/results.json` with source and exact policies. Business evidence is in `.run/mxc-native-adaptation`.

No full native Windows project build, production Maven wrapper, full model conversation or packaged Electron installer was validated. Earlier Windows-to-Linux reports are historical.
