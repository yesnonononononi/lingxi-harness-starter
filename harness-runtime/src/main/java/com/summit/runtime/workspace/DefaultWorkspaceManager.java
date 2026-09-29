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
    /**
     * Fixed-size lock stripes: a bounded number of monitors instead of one object per workspace ref.
     * Different refs may share a stripe and serialise slightly, but memory stays bounded no matter
     * how many refs are seen over the lifetime of the manager.
     *
     * <p>64 is an experience-based value, not a measured one: it only has to stay well above the
     * number of workspace refs manipulated concurrently to make a stripe collision rare, and it
     * costs a constant 64 monitors for the lifetime of the manager. Raising it lowers the collision
     * probability further at the price of a few more objects; lowering it below the expected
     * concurrency would start serialising unrelated workspaces.</p>
     *
     * <p>The stripe is picked with {@code Math.floorMod} rather than {@code hash & (LOCK_STRIPES-1)}
     * on purpose. {@code floorMod} is correct for any positive stripe count — including negative
     * hashes — so the constant above can be retuned without first re-deriving a bit mask. The power
     * of two is a convenient default, not a requirement of the arithmetic.</p>
     */
    private static final int LOCK_STRIPES = 64;
    private final Object[] lockStripes = createLockStripes();
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
        // Striping keeps memory bounded: the same ref always maps to the same stripe, so mutual
        // exclusion holds (and re-entrancy is safe); unrelated refs sharing a stripe merely
        // serialise slightly.
        Object lock = lockStripes[Math.floorMod(ref.id().hashCode(), LOCK_STRIPES)];
        synchronized (lock) {
            return action.get();
        }
    }

    private static Object[] createLockStripes() {
        Object[] stripes = new Object[LOCK_STRIPES];
        for (int index = 0; index < stripes.length; index++) {
            stripes[index] = new Object();
        }
        return stripes;
    }
}
