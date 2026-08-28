package com.summit.runtime.tool;

import com.summit.harnesscore.tool.PatchEntity;
import com.summit.harnesscore.tool.PatchStore;
import org.jspecify.annotations.NonNull;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class DefaultPatchStore implements PatchStore {
    private final Map<Serializable, List<PatchEntity>> patches = new ConcurrentHashMap<>();


    @Override
    public void put(@NonNull Serializable sessionId, @NonNull PatchEntity patch) {
        Serializable id = patch.id();
        if(id == null)throw new IllegalArgumentException("patch id cannot be null");
        this.patches.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(patch);
    }

    @Override
    public void put(@NonNull PatchEntity patch) {
        this.put(patch.sessionId(),patch);
    }

    @Override
    public Optional<PatchEntity> get(@NonNull Serializable sessionId, @NonNull Serializable id) {
        List<PatchEntity> patchEntities = this.patches.get(sessionId);
        if (patchEntities == null) {
            return Optional.empty();
        }
        return patchEntities.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    @Override
    public void removeBySessionId(@NonNull Serializable sessionId) {
        this.patches.remove(sessionId);
    }

    @Override
    public boolean removeById(@NonNull Serializable sessionId, @NonNull Serializable patchId) {
        List<PatchEntity> patchEntities = this.patches.get(sessionId);
        return patchEntities != null && patchEntities.removeIf(p -> p.id().equals(patchId));
    }

    @Override
    public Map<Serializable, List<PatchEntity>> getAll() {
        return this.patches.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        e -> List.copyOf(e.getValue())
                ));
    }


    @Override
    public void clear() {
        this.patches.clear();
    }
}
