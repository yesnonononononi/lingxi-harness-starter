package com.summit.runtime.tool;

import com.github.difflib.patch.PatchFailedException;
import com.summit.harnesscore.runtime.Workspace;
import com.summit.harnesscore.runtime.WorkspaceBridge;
import com.summit.harnesscore.tool.FileHasher;
import com.summit.harnesscore.tool.PatchEntity;
import com.summit.harnesscore.tool.PatchManager;
import com.summit.harnesscore.tool.PatchStore;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Default {@link PatchManager} implementation.
 * <p>
 * Composes the {@link PatchStore} persistence primitives with the business rules:
 * id generation on save, hash-based conflict detection before applying and
 * removal of the patch after a successful apply.
 */
@Slf4j
@RequiredArgsConstructor
public class DefaultPatchManager implements PatchManager {

    private final PatchStore patchStore;
    private final FileHasher fileHasher;

    @Override
    public Serializable savePatch(@NonNull Serializable sessionId, @NonNull PatchEntity patch) {
        Serializable id = patch.id() != null ? patch.id() : UUID.randomUUID();
        PatchEntity saved = PatchEntity.builder()
                .id(id)
                .sessionId(sessionId)
                .patch(patch.patch())
                .filePath(patch.filePath())
                .fileContentHash(patch.fileContentHash())
                .build();
        this.patchStore.put(sessionId, saved);
        log.info("Saved patch {} for session {} on file {}", id, sessionId, patch.filePath());
        return id;
    }

    @Override
    public Optional<PatchEntity> getPatch(@NonNull Serializable sessionId, @NonNull Serializable patchId) {
        return this.patchStore.get(sessionId, patchId);
    }

    @Override
    public List<PatchEntity> listPatches(@NonNull Serializable sessionId) {
        List<PatchEntity> patches = this.patchStore.getAll().get(sessionId);
        return patches != null ? patches : List.of();
    }

    @Override
    public boolean discardPatch(@NonNull Serializable sessionId, @NonNull Serializable patchId) {
        boolean removed = this.patchStore.removeById(sessionId, patchId);
        if (removed) {
            log.info("Discarded patch {} for session {}", patchId, sessionId);
        }
        return removed;
    }

    @Override
    public void discardPatches(@NonNull Serializable sessionId) {
        this.patchStore.removeBySessionId(sessionId);
        log.info("Discarded all patches for session {}", sessionId);
    }

    @Override
    public boolean applyPatch(@NonNull Serializable sessionId, @NonNull Serializable pId) throws IOException, PatchFailedException {
        return applyPatch(sessionId, pId, StandardCharsets.UTF_8);
    }

    @Override
    public boolean applyPatch(@NonNull Serializable sessionId, @NonNull Serializable pId, Charset charset) throws IOException, PatchFailedException {
        PatchEntity patch = loadApplicablePatch(sessionId, pId);
        if (patch == null) {
            return false;
        }
        Path path = Path.of(patch.filePath());
        if (Files.notExists(path)) {
            throw new FileNotFoundException("File not found: " + patch.filePath());
        }

        List<String> lines = applyToLines(patch, Files.readString(path, charset));
        Files.write(path, lines, charset);
        this.patchStore.removeById(sessionId, pId);
        log.info("Applied patch {} for session {} on file {}", pId, sessionId, patch.filePath());
        return true;
    }

    @Override
    public boolean applyPatch(@NonNull Serializable sessionId, @NonNull Serializable pId,
                              @NonNull Workspace workspace) throws IOException, PatchFailedException {
        PatchEntity patch = loadApplicablePatch(sessionId, pId);
        if (patch == null) {
            return false;
        }

        WorkspaceBridge bridge = workspace.bridge();
        Path path = workspace.resolve(patch.filePath()).normalize();
        if (!bridge.exists(path)) {
            throw new FileNotFoundException("File not found: " + path);
        }

        Charset charset = workspace.runtimeEnvironment().charset();
        List<String> lines = applyToLines(patch, bridge.readString(path, charset));
        bridge.writeString(path, String.join(System.lineSeparator(), lines), charset);
        this.patchStore.removeById(sessionId, pId);
        log.info("Applied patch {} for session {} on file {} in workspace {}", pId, sessionId, patch.filePath(), workspace.id());
        return true;
    }

    /**
     * Loads the patch for the given session and checks it is applicable at all
     * (it exists and carries patch content). Returns {@code null} when the
     * patch has no content and should be reported as not applied.
     */
    private PatchEntity loadApplicablePatch(Serializable sessionId, Serializable pId) {
        PatchEntity patch = this.patchStore.get(sessionId, pId)
                .orElseThrow(() -> new IllegalArgumentException("Patch does not exist: " + pId));
        if (patch.patch() == null) {
            log.warn("Patch content is null for patch {} on file {}", pId, patch.filePath());
            return null;
        }
        return patch;
    }

    /**
     * Verifies the recorded content hash against the current file content,
     * applies the patch and returns the resulting content as lines.
     */
    private List<String> applyToLines(PatchEntity patch, String currentContent) throws PatchFailedException {
        if (!Objects.equals(patch.fileContentHash(), this.fileHasher.hash(currentContent))) {
            throw new RuntimeException("File hash mismatch for patch " + patch.id() + ", skip applying");
        }
        List<String> lines = new ArrayList<>(Arrays.asList(currentContent.split("\\R", -1)));
        patch.patch().applyToExisting(lines);
        return lines;
    }


}
