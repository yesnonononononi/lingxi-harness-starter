package com.summit.runtime.workspace;

import com.summit.core.runtime.Workspace;
import com.summit.core.workspace.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Default provider registry and lifecycle coordinator. */
public final class DefaultWorkspaceManager implements WorkspaceManager {
    private final Map<String, WorkspaceProvider> providers;
    private final WorkspaceStore store;
    private final Map<WorkspaceRef, Object> locks = new ConcurrentHashMap<>();

    public DefaultWorkspaceManager(Collection<WorkspaceProvider> providers,
                                   WorkspaceStore store) {
        if (providers == null || providers.isEmpty()) {
            throw new IllegalArgumentException("at least one workspace provider is required");
        }
        this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(
                provider -> normalize(provider.type()),
                provider -> provider,
                (left, right) -> {
                    throw new IllegalArgumentException("duplicate workspace provider: " + left.type());
                }));
        this.store = Objects.requireNonNull(store, "workspace store");
    }

    @Override
    public WorkspaceRecord create(WorkspaceSpec spec) {
        return create(new WorkspaceRef(UUID.randomUUID().toString()), spec);
    }

    @Override
    public WorkspaceRecord create(WorkspaceRef ref, WorkspaceSpec spec) {
        return locked(ref, () -> {
            if (store.find(ref).isPresent()) {
                throw new IllegalStateException("workspace already exists: " + ref.id());
            }
            WorkspaceRecord record = provider(spec.provider()).provision(ref, spec);
            store.save(record);
            return record;
        });
    }

    @Override
    public void register(WorkspaceRecord record) {
        provider(record.spec().provider());
        locked(record.ref(), () -> {
            store.save(record);
            return null;
        });
    }

    @Override
    public Workspace acquire(WorkspaceRef ref) {
        return locked(ref, () -> {
            WorkspaceRecord record = requireRecord(ref);
            WorkspaceProvider provider = provider(record.spec().provider());
            WorkspaceRecord reconciled = provider.reconcile(record);
            if (!reconciled.equals(record)) {
                store.save(reconciled);
            }
            return provider.open(reconciled);
        });
    }

    @Override
    public WorkspaceRecord reconcile(WorkspaceRef ref) {
        return locked(ref, () -> {
            WorkspaceRecord record = requireRecord(ref);
            WorkspaceRecord reconciled = provider(record.spec().provider()).reconcile(record);
            if (!reconciled.equals(record)) {
                store.save(reconciled);
            }
            return reconciled;
        });
    }

    @Override
    public WorkspaceStatus inspect(WorkspaceRef ref) {
        WorkspaceRecord record = requireRecord(ref);
        return provider(record.spec().provider()).inspect(record);
    }

    @Override
    public void destroy(WorkspaceRef ref) {
        locked(ref, () -> {
            WorkspaceRecord record = requireRecord(ref);
            provider(record.spec().provider()).destroy(record);
            store.delete(ref);
            return null;
        });
    }

    private WorkspaceRecord requireRecord(WorkspaceRef ref) {
        return store.find(ref).orElseThrow(() ->
                new IllegalArgumentException("workspace not found: " + ref.id()));
    }

    private WorkspaceProvider provider(String type) {
        WorkspaceProvider provider = providers.get(normalize(type));
        if (provider == null) {
            throw new IllegalArgumentException("workspace provider is not installed: " + type);
        }
        return provider;
    }

    private static String normalize(String type) {
        return type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
    }

    private <T> T locked(WorkspaceRef ref, Supplier<T> action) {
        Object lock = locks.computeIfAbsent(ref, ignored -> new Object());
        synchronized (lock) {
            return action.get();
        }
    }
}
