# Local SRT workspaces

Public APIs are in `com.summit.sandbox`. Register a `SandboxWorkspaceProvider` as
a `WorkspaceProvider` bean, or call it directly outside Spring:

```java
SandboxWorkspaceProvider provider = SandboxWorkspaceProvider.builder()
        .applicationDirectory(applicationDirectory)
        .nodeExe(nodeExecutable)
        .srtCli(srtCliEntryPoint)
        .build();

WorkspaceSpec spec = SandboxWorkspaceSpec.of(workDirectory, SandboxPolicy.builder()
        .denyRead(List.of("secrets"))
        .denyWrite(List.of("readonly"))
        .allowedDomains(List.of("api.example.com"))
        .build());
Workspace workspace = provider.resolve(spec);
workspace.bridge().writeString(workspace.resolve("result.txt"), "ready", StandardCharsets.UTF_8);
```

The existing `WorkspaceResolver` selects this provider for `provider = "sandbox"`.
`SandboxWorkspaceSpec.of` returns the framework's built-in `BasicWorkspaceSpec`,
so execution snapshot serialization and recovery need no custom subtype registration.
The optional scope overload keeps different principals' workspace identities separate.
It does not provide separate OS sandbox accounts for simultaneous tenants.

`resolve` performs the complete startup sequence: extracts helpers, provisions a
request directory, generates settings, checks Node/SRT using those exact settings,
and runs a readiness helper under the same policy as subsequent tool calls.
It returns a workspace only after file create/read/write/delete, configured shell
execution, and protection of helper/request/settings directories succeed. Existing
`denyRead` files and one existing file under each denied directory are also checked;
existing `denyWrite` targets are probed without changing existing file contents.
This is a startup check, not an exhaustive audit of the OS sandbox or network policy.
An ineffective policy or failed dependency fails initialization with diagnostics;
there is no fallback to host execution or automatic privileged installation.

The generated settings allow workspace writes plus the policy's additional
`allowWrite` paths. A `denyWrite` entry covering the workspace root suppresses its
automatic write grant; readiness then checks reading and rejected writes instead
of creating files in the read-only workspace. The provider adds runtime/helper/request reads and denies writes
to its helper and state directories. Network access defaults to an empty domain
allowlist. Policy filesystem paths are literal, and relative paths resolve against
the workspace root; glob expansion is not part of this typed API.
Each workspace/scope/policy gets its own complete settings file, published atomically;
the provider does not modify the user's global SRT settings. State defaults to
`<applicationDirectory>/resources/sandbox-state/`; use `stateDirectory` when the
application's installation is read-only. The deployment owns removal of persisted
state after workspaces are no longer needed. Per-call request files are removed
by the bridge. The workspace retains one immutable bridge instance.

Filesystem rules have the semantics of the supplied SRT backend. Windows SRT uses
additive ACL grants, which do not revoke ambient group write permissions outside
explicit denied paths. An `allowWrite` list and a readiness sample therefore do
not establish a portable, exhaustive write boundary. This provider accepts both
writable and read-only project policies and verifies their explicit restrictions.
Applications own tool exposure: a read-only application mode may disable editing
while retaining terminal commands whose write effects are outside that mode's
guarantee. Runtime OS metadata must match the actual local host.

The local provider derives OS and shell defaults from the host. `environment` and
`timeout` builder options can explicitly select a supported local environment and
startup/file-operation time limits. Shell wrapping always belongs to the bridge.
Supplying `bashExe` selects Bash and its absolute executable path within the framework;
applications still pass raw scripts. `RuntimeEnvironment.shellExecutable` can override
other shell executable locations without changing their argument syntax. The supplied
Bash bundle receives read access, and Node/Bash directories lead the command's PATH.
The same environment is used for the readiness shell and subsequent command helpers.

The application supplies the bundled Node executable and the JavaScript entry point
of a complete `@anthropic-ai/sandbox-runtime` installation (`dist/cli.js`, not an
npm shell shim). This module does not yet bundle or extract those resources.

The application supplies `applicationDirectory`, its absolute installation root.
`HelperLoader.resolve(applicationDirectory, name)` extracts classpath `.mjs` resources
to `resources/sandbox-helpers/` relative to that root, including when packaged in a JAR.
This location is independent of the process working directory. Helpers persist across
restarts; first use verifies them against the current JAR and replaces stale files,
while subsequent calls reuse verified files without rereading their script content.
The installer can pre-extract helpers when the application account cannot write the
installation directory. Deployment must grant sandbox read/execute access to this
directory and its necessary ancestor directories, and deny sandbox writes to it.

`SandboxWorkspaceBridge.execute` accepts raw command text. It selects the workspace
shell internally and sends its argv as JSON to `executeCommand.mjs` running
inside SRT. The helper starts that shell with `shell: false`; the command is never
flattened into the SRT helper invocation. PowerShell scripts use UTF-16LE encoded
commands, and CMD scripts retain the `/d /s /c` outer quotes without CRT re-escaping.
Node executes the installed `.mjs` file directly, with an encoded request-file path
as a normal argument. `request.mjs` reads that JSON; there is no inline module or Node
`--eval` execution. The host-side `srtCli.mjs` adapter reconstructs the helper invocation from
Base64 before loading the supplied SRT CLI, avoiding Java's Windows quote stripping.
The SRT CLI's default helper shell and the workspace's command shell are separate.
The bootstrap uses the OS declared by `RuntimeEnvironment`, rather than inspecting
the developer machine. Windows helpers use a PowerShell encoded bootstrap with literal paths; POSIX helpers
use shell-quoted paths. Spaces, quotes, percent signs and dollar signs in file paths
are transported as JSON data, rather than interpreted by a shell.

Each call stages its JSON in a private request file. This transport does not depend
on whether a runtime forwards stdin. Set `requestDirectory` to an existing directory
provisioned by the workspace provider; its SRT policy and OS permissions must allow
the sandbox to read it and deny sandbox writes to it. The host must be able to create
and remove request files there. Omitting it uses the JVM temporary directory, which
must satisfy the same deployment requirements. The bridge removes each request and
its child directory on success, failure, interruption or timeout.

All six bridge operations execute through SRT. File reads return Base64 bytes and
are decoded with the requested charset, preserving BOMs, blank lines and the final
newline state. Invalid text encodings and failed file operations raise `IOException`.
`exists` returns false only for missing paths; backend failure raises
`UncheckedIOException` because the interface has no checked exception for that method.

For direct low-level bridge construction, the optional `settingsFile` parameter is
passed unchanged to SRT's `--settings`. Omitting it leaves policy lookup and defaults
entirely to SRT. The bridge does not parse, copy, rewrite or supplement deployment
policies. The application supplies the policy and provisions its external resources. Workspace
path resolution normalizes relative and absolute paths; SRT authorizes the actual IO.

The opt-in `SandboxWorkspaceBridgeIntegrationTest` uses a standard supplied SRT distribution,
temporary allowed/restricted files, and a policy selected by the test. Run it with
`-Dsandbox.srt.cli=<absolute dist/cli.js>`. Native helper selection remains with SRT;
the test does not require a locally patched helper or a particular sandbox username.
The installation fixture lives under `target/sandbox-installations` by default;
`-Dsandbox.test.installRoot=<directory>` can select another deployment test root.
It never calls installation commands. Platform branches cover Windows and POSIX;
only platforms actually exercised should be reported as runtime-verified.

`SandboxInitializer.init(srtCli, node)` validates regular files, runs Node's
`--version`, checks that the SRT CLI loads with `--version`, then runs
`node <cli> -c whoami`. Executables and arguments are passed directly to
`ProcessBuilder`; workspace shell selection and Git Bash are independent.
The `init(srtCli, node, settingsFile)` overload probes an explicit policy; the
provider always uses this overload before the bridge readiness probe.
Failures retain the exit code and combined command output.

`ProcessRunner.run(command, stdin, timeout, unit[, maxCharsLimit])` delivers the
supplied bytes to the child's standard input and closes the pipe to signal EOF.
Input delivery runs concurrently with output draining and is covered by the
execution timeout. Overloads without input also close stdin, allowing helpers
that read until EOF to finish.

The runner lives in `harness-core` and is shared with `LocalWorkspaceBridge`.
Its list-based overloads accept a working directory, output charset and timeout
unit. Output capture respects the character budget while continuing to drain
the process pipe, preserving original line endings and final newline state.

Checking never invokes `windows-install`. Applications can explicitly invoke
`installWindows(srtCli, node)` to provision the Windows sandbox account and WFP
components. This operation may prompt for UAC; component checks run before
installation and one sandbox probe runs after a successful install.

Initializer checks alone establish CLI loading and basic sandbox execution. They do not
validate bundled versions or architectures, verify every package asset, or prove
filesystem and network restrictions. Git Bash validation belongs to the execution
environment that chooses to use it.
