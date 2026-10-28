package com.summit.runtime.workspace;

import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.workspace.WorkspaceProvider;
import com.summit.core.workspace.WorkspaceResolver;
import com.summit.core.workspace.WorkspaceSpec;

import java.util.*;

/** Stateless provider routing; providers retain control over resource reuse and cleanup. */
public final class DefaultWorkspaceResolver implements WorkspaceResolver {
    private final Map<String, WorkspaceProvider> providers;

    public DefaultWorkspaceResolver(Collection<WorkspaceProvider> providers) {
        if (providers == null || providers.isEmpty()) {
            throw new IllegalArgumentException("at least one workspace provider is required");
        }
        Map<String, WorkspaceProvider> registered = new HashMap<>();
        for (WorkspaceProvider provider : providers) {
            Objects.requireNonNull(provider, "workspace provider");
            String type = normalize(provider.type());
            if (registered.putIfAbsent(type, provider) != null) {
                throw new IllegalArgumentException("duplicate workspace provider: " + type);
            }
        }
        this.providers = Map.copyOf(registered);
    }

    @Override
    public Workspace resolve(WorkspaceSpec spec) {
        Objects.requireNonNull(spec, "workspace spec");
        WorkspaceProvider provider = providers.get(normalize(spec.provider()));
        if (provider == null) {
            throw new IllegalArgumentException("workspace provider is not installed: " + spec.provider());
        }
        return Objects.requireNonNull(provider.resolve(spec), "resolved workspace");
    }

    private static String normalize(String type) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("workspace provider type must not be blank");
        }
        return type.trim().toLowerCase(Locale.ROOT);
    }
}
