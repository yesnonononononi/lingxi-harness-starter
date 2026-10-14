package com.summit.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.summit.core.runtime.RuntimeEnvironment;
import com.summit.core.runtime.workspace.OsType;
import com.summit.core.runtime.workspace.ShellType;
import com.summit.core.workspace.WorkspaceProvider;
import com.summit.core.workspace.WorkspaceRef;
import com.summit.core.workspace.WorkspaceSpec;
import com.summit.sandbox.internal.HelperLoader;
import lombok.Builder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Prepares and verifies local SRT workspaces without changing machine-wide installations.
 */
public final class SandboxWorkspaceProvider implements WorkspaceProvider {
    public static final String TYPE = "sandbox";
    private final Path applicationDirectory;
    private final Path srtCli;
    private final Path nodeExe;
    private final Path stateDirectory;
    private final RuntimeEnvironment environment;
    private final ObjectMapper mapper;
    private final CommandTimeoutConfig timeout;

    @Builder
    public SandboxWorkspaceProvider(Path applicationDirectory, Path srtCli, Path nodeExe, Path stateDirectory,
                                    RuntimeEnvironment environment, ObjectMapper objectMapper,
                                    CommandTimeoutConfig timeout) {
        this.applicationDirectory = absolute(applicationDirectory, "applicationDirectory");
        this.srtCli = absolute(srtCli, "srtCli");
        this.nodeExe = absolute(nodeExe, "nodeExe");

        this.stateDirectory = stateDirectory == null
                ? this.applicationDirectory.resolve("resources/sandbox-state") : absolute(stateDirectory, "stateDirectory");

        RuntimeEnvironment selected = environment == null ? localEnvironment() : environment;
        if (selected.osType() != localEnvironment().osType()) {
            throw new IllegalArgumentException("Sandbox runtime OS must match the local host; "
                    + "environment metadata cannot change the SRT backend");
        }
        this.environment = new RuntimeEnvironment(Objects.requireNonNull(selected.charset(), "environment charset"),
                selected.envs(), selected.osType(), selected.shellType(), true);
        this.mapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.timeout = timeout == null ? new CommandTimeoutConfig(60, TimeUnit.SECONDS) : timeout;
    }

    @Override
    public String type() {
        return TYPE;
    }

    /**
     * Returns a workspace only after its exact generated policy and execution path pass a readiness probe.
     * @throws UnsupportedOperationException when the local backend cannot enforce the requested write boundary
     */
    @Override
    public SandboxWorkspace resolve(WorkspaceSpec spec) {
        Objects.requireNonNull(spec, "workspace spec");

        if (spec.provider() == null || !TYPE.equalsIgnoreCase(spec.provider().trim())) {
            throw new IllegalArgumentException("unsupported workspace provider: " + spec.provider());
        }

        try {
            SandboxPolicy requestedPolicy = Objects.requireNonNull(SandboxWorkspaceSpec.policy(spec), "sandbox policy");

            Path root = absolute(Path.of(spec.workDir()), "").toRealPath();

            if (!Files.isDirectory(root)) throw new IOException("Sandbox workspace is not a directory: " + root);

            // Windows SRT adds grants; it does not revoke ambient Users/Everyone write access.
            // Sampling a few denied paths cannot establish an allowlist for arbitrary commands.
            if (environment.osType() == OsType.WINDOWS && requestedPolicy.restrictWrites()) {
                throw new UnsupportedOperationException("Windows SRT cannot enforce a filesystem write allowlist; "
                        + "restricted-write policies are unsupported. No sandbox process was started.");
            }

            Path helpers = HelperLoader.extractAll(applicationDirectory);

            String identity = TYPE + "|" + Objects.requireNonNullElse(spec.scope(), "").trim() + "|" + root;

            String id = WorkspaceRef.derived(TYPE, identity).id();

            // Separate policies never overwrite each other's settings, including concurrent resolves.
            String policyId = WorkspaceRef.derived("policy",
                            mapper.writeValueAsString(
                                    List.of(applicationDirectory.toString(), nodeExe.toString(), requestedPolicy))
                    )
                    .id();

            Path state = Files.createDirectories(stateDirectory.resolve(id).resolve(policyId)).toRealPath();

            // {app}/resources/sandbox-state/{id}/{policy}/requests
            // {app}/resources/sandbox-state/{id}/{policy}/settings.json
            // Request files are a transport choice, not a Node requirement. The supported
            // Windows SRT backend uses stdin for its native control protocol instead of
            // forwarding business stdin to sandboxed helpers. JSON files keep command text
            // and Base64 file contents out of shell quoting and command-line length limits.
            // The sandbox may read this directory but must not write to it. The bridge's
            // try-with-resources scope removes each request file and child directory after use.
            Path requests = Files.createDirectories(state.resolve("requests"));

            Path settings = state.resolve("settings.json");

            ObjectNode policy = settings(root, helpers, state, requests, requestedPolicy);

            HelperLoader.publish(settings, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(policy));

            SandboxWorkspace workspace = SandboxWorkspace.builder()
                    .id(id)
                    .workDir(root.toString())
                    .runtimeEnvironment(environment)
                    .objectMapper(mapper)
                    .timeout(timeout)
                    .srtCli(srtCli)
                    .nodeExe(nodeExe)
                    .settingsFile(settings)
                    .requestDirectory(requests)
                    .applicationDirectory(applicationDirectory)
                    .build();

            new SandboxInitializer(timeout, workspace).init(srtCli, nodeExe, settings);

            workspace.bridge().verifyReady(root,
                    List.of(helpers, requests, state),
                    paths(root, requestedPolicy.denyRead()),
                    paths(root, requestedPolicy.denyWrite()),
                    workspaceWritable(root, requestedPolicy)
            );

            return workspace;
        } catch (IOException e) {
            throw new UncheckedIOException("Sandbox workspace initialization failed", e);
        }
    }

    private ObjectNode settings(Path root, Path helpers, Path state, Path requests, SandboxPolicy policy) {
        List<Path> writes = new ArrayList<>(paths(root, policy.allowWrite()));

        if (workspaceWritable(root, policy)) writes.add(root);

        List<Path> reads = new ArrayList<>(paths(root, policy.allowRead()));

        reads.addAll(List.of(root, helpers, requests, nodeExe.getParent()));

        List<Path> deniedWrites = new ArrayList<>(paths(root, policy.denyWrite()));

        deniedWrites.addAll(List.of(helpers, state));

        ObjectNode result = mapper.createObjectNode();

        result.set("network",
                mapper.valueToTree(
                        Map.of("allowedDomains", policy.allowedDomains(),
                                "deniedDomains", policy.deniedDomains())
                )
        );

        result.set("filesystem", mapper.valueToTree(Map.of("allowWrite", literals(writes),
                "allowRead", literals(reads), "denyRead", literals(paths(root, policy.denyRead())),
                "denyWrite", literals(deniedWrites))));

        return result;
    }

    private static List<Map<String, Object>> literals(List<Path> paths) {
        return new LinkedHashSet<>(paths)
                .stream()
                .map(path -> Map.<String, Object>of("path", path.toString(), "literal", true))
                .toList();
    }

    private static boolean workspaceWritable(Path root, SandboxPolicy policy) {
        return paths(root, policy.denyWrite()).stream().noneMatch(root::startsWith);
    }

    private static List<Path> paths(Path root, List<String> paths) {
        return paths.stream()
                .map(value -> {
                    if (value.isBlank()) throw new IllegalArgumentException("Sandbox policy path must not be blank");
                    return root.resolve(value).toAbsolutePath().normalize();
                })
                .distinct()
                .toList();
    }

    private static Path absolute(Path path, String name) {
        return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
    }

    private static RuntimeEnvironment localEnvironment() {

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

        OsType type = os.contains("win") ? OsType.WINDOWS : os.contains("mac") ? OsType.MACOS
                : os.contains("linux") ? OsType.LINUX : OsType.UNKNOWN;

        if (type == OsType.UNKNOWN) throw new IllegalStateException("Unsupported local sandbox OS: " + os);

        return RuntimeEnvironment.builder()
                .osType(type)
                .shellType(type == OsType.WINDOWS ? ShellType.POWERSHELL : ShellType.SH)
                .charset(StandardCharsets.UTF_8).envs(Map.of())
                .isolated(true)
                .build();
    }
}
