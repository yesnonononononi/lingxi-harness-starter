package com.summit.runtime.workspace;

import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.workspace.*;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Default provider registry and lifecycle coordinator. */
@Slf4j
public final class DefaultWorkspaceManager implements WorkspaceManager {
    private final Map<String, WorkspaceProvider> providers;
    private final WorkspaceStore store;
    private final Map<WorkspaceRef, Object> locks = new ConcurrentHashMap<>();
    /** Natural key of a spec to the workspace that was provisioned for it. */
    private final ConcurrentMap<String, WorkspaceRef> identityIndex = new ConcurrentHashMap<>();

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
        Objects.requireNonNull(spec, "workspace spec");
        WorkspaceProvider provider = provider(spec.provider());
        String naturalKey = provider.identityKey(spec);
        WorkspaceRef ref = WorkspaceRef.derived(spec.provider(), naturalKey);
        return locked(ref, () -> {
            // The identity is a pure function of the spec, so provisioning the
            // same configuration twice is a no-op rather than a conflict.
            WorkspaceRecord existing = store.find(ref).orElse(null);
            if (existing != null) {
                identityIndex.putIfAbsent(naturalKey, ref);
                return existing;
            }
            WorkspaceRecord record = provider.provision(ref, spec);
            try {
                store.save(record);
                identityIndex.put(naturalKey, ref);
                return record;
            } catch (RuntimeException | Error saveFailure) {
                try {
                    provider.destroy(record);
                } catch (RuntimeException | Error cleanupFailure) {
                    saveFailure.addSuppressed(cleanupFailure);
                }
                throw saveFailure;
            }
        });
    }

    @Override
    public WorkspaceRecord resolve(WorkspaceSpec spec) {
        Objects.requireNonNull(spec, "workspace spec");
        WorkspaceProvider provider = provider(spec.provider());
        String naturalKey = provider.identityKey(spec);
        WorkspaceRef indexed = identityIndex.get(naturalKey);
        if (indexed != null && store.find(indexed).isPresent()) {
            return reconcile(indexed);
        }
        return create(spec);
    }

    @Override
    public void register(WorkspaceRecord record) {
        WorkspaceProvider provider = provider(record.spec().provider());
        locked(record.ref(), () -> {
            store.save(record);
            identityIndex.putIfAbsent(provider.identityKey(record.spec()), record.ref());
            return null;
        });
    }

    /**
     * Adopts everything the installed providers can recognise, one provider at a
     * time: an unreadable backing service must not hide the resources another
     * provider could still restore, so discovery failures are logged and skipped
     * rather than propagated. The workspaces themselves then resurface lazily
     * through {@link #resolve(WorkspaceSpec)}.
     */
    @Override
    public int restoreManagedRecords() {
        int restored = 0;
        for (WorkspaceProvider provider : providers.values()) {
            List<WorkspaceRecord> discovered;
            try {
                discovered = provider.discoverManagedRecords();
            } catch (RuntimeException failure) {
                log.warn("workspace discovery failed for provider {}: {}", provider.type(), failure.getMessage());
                continue;
            }
            for (WorkspaceRecord record : discovered) {
                if (adopt(record)) {
                    restored++;
                }
            }
        }
        return restored;
    }

    /** Registers {@code record} unless one is already stored for the same ref; true when it registered. */
    private boolean adopt(WorkspaceRecord record) {
        try {
            // Checking and storing share the ref lock, so two concurrent restores
            // cannot both adopt the same resource.
            return locked(record.ref(), () -> {
                if (store.find(record.ref()).isPresent()) {
                    return false;
                }
                register(record);
                return true;
            });
        } catch (RuntimeException failure) {
            log.warn("skipping undiscoverable workspace {}: {}", record.ref().id(), failure.getMessage());
            return false;
        }
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
            WorkspaceRecord record = store.find(ref).orElse(null);
            if (record == null) {
                return null;
            }
            WorkspaceProvider provider = provider(record.spec().provider());
            provider.destroy(record);
            store.delete(ref);
            identityIndex.remove(provider.identityKey(record.spec()), ref);
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
