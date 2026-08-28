package com.summit.harnesscore.tool;

import lombok.NonNull;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface PatchStore {
    void put(@NonNull Serializable sessionId, @NonNull PatchEntity patch);
    void put(@NonNull PatchEntity patch);

    Optional<PatchEntity> get(@org.jspecify.annotations.NonNull Serializable sessionId, @org.jspecify.annotations.NonNull Serializable id);

    void removeBySessionId(@NonNull Serializable sessionId);
    boolean removeById(@NonNull Serializable sessionId, @NonNull Serializable patchId);
    Map<Serializable, List<PatchEntity>> getAll();
    void clear();
}
