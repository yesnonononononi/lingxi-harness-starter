package com.summit.sandbox.mxc;

import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Resolves host context at execution time without adding credentials to workspace specifications. */
final class MxcHostEnvironment {
    private MxcHostEnvironment() { }

    static Map<String, String> resolve(Map<String, String> overrides, MxcRuntime runtime) {
        boolean windows = runtime.windows();
        Map<String, String> environment = new LinkedHashMap<>(resolve(System.getenv(), overrides, windows));
        if (overrides.keySet().stream().noneMatch(key -> windows ? key.equalsIgnoreCase("PATH") : key.equals("PATH"))) {
            String path = environment.getOrDefault("PATH", windows ? System.getenv("SystemRoot") + "\\System32" : "/usr/bin:/bin");
            // The runtime Node is granted by the sandbox; a host Node installation may not be readable.
            environment.put("PATH", Path.of(runtime.nodeExecutable()).getParent() + File.pathSeparator + path);
        }
        return Map.copyOf(environment);
    }

    static Map<String, String> resolve(Map<String, String> host, Map<String, String> overrides, boolean windows) {
        Map<String, String> environment = new LinkedHashMap<>();
        host.forEach((key, value) -> {
            // Windows drive-current-directory entries are not ordinary environment variables.
            if (!key.isEmpty() && !key.contains("=")) environment.put(windows ? key.toUpperCase(Locale.ROOT) : key, value);
        });
        overrides.forEach((key, value) -> environment.put(windows ? key.toUpperCase(Locale.ROOT) : key, value));
        if (windows && environment.containsKey("USERPROFILE")) environment.putIfAbsent("HOME", environment.get("USERPROFILE"));
        return Map.copyOf(environment);
    }
}
