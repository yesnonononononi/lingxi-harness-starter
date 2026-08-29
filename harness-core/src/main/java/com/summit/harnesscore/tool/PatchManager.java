package com.summit.harnesscore.tool;


import com.github.difflib.patch.PatchFailedException;
import lombok.NonNull;

import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Optional;

/**
 * Business facade for patch lifecycle management.
 * <p>
 * Encapsulates the business rules (id generation, hash-based conflict detection,
 * apply-and-remove) on top of the {@link PatchStore} persistence primitives.
 */
public interface PatchManager {

    /**
     * Persist a patch for the given session, generating its id when absent.
     *
     * @param sessionId session id
     * @param patch     the patch to save
     * @return the generated (or existing) patch id
     */
    Serializable savePatch(@NonNull Serializable sessionId, @NonNull PatchEntity patch);

    /**
     * Query a single patch by session and patch id.
     *
     * @param sessionId session id
     * @param patchId   patch id
     * @return the patch if present
     */
    Optional<PatchEntity> getPatch(@NonNull Serializable sessionId, @NonNull Serializable patchId);

    /**
     * List all patches of the given session.
     *
     * @param sessionId session id
     * @return the patches of the session, never {@code null}
     */
    List<PatchEntity> listPatches(@NonNull Serializable sessionId);

    /**
     * Discard a single patch without applying it.
     *
     * @param sessionId session id
     * @param patchId   patch id
     * @return {@code true} if the patch existed and was removed
     */
    boolean discardPatch(@NonNull Serializable sessionId, @NonNull Serializable patchId);

    /**
     * Discard all patches of the given session.
     *
     * @param sessionId session id
     */
    void discardPatches(@NonNull Serializable sessionId);

    /**
     * Apply a patch to its target file with default charset UTF-8.
     * <p>
     * The current file content hash is compared against the hash recorded at
     * save time; if they differ the patch is NOT applied and {@code false} is
     * returned. On success the applied patch is removed from the store.
     *
     * @param sessionId session id
     * @param pId       patch id
     * @return {@code true} if the patch was applied, {@code false} on hash mismatch
     * @throws IOException            if the file cannot be read or written
     * @throws PatchFailedException   if the patch cannot be applied to the file content
     */
    boolean applyPatch(@NonNull Serializable sessionId, @NonNull Serializable pId) throws IOException, PatchFailedException;

    /**
     * Apply a patch to its target file with the given charset.
     *
     * @param sessionId session id
     * @param pId       patch id
     * @param charset   the charset to use for reading and writing the file
     * @return {@code true} if the patch was applied, {@code false} on hash mismatch
     * @throws IOException          if the file cannot be read or written
     * @throws PatchFailedException if the patch cannot be applied to the file content
     */
    boolean applyPatch(@NonNull Serializable sessionId, @NonNull Serializable pId, Charset charset) throws IOException, PatchFailedException;

    /**
     * Apply a patch to its target file inside the given workspace, using the
     * workspace's own {@link com.summit.harnesscore.runtime.WorkspaceBridge}
     * for file IO and resolving the stored (relative) path against the
     * workspace boundary. Use this overload for sandbox-backed workspaces.
     *
     * @param sessionId session id
     * @param pId       patch id
     * @param workspace workspace to resolve and write the file in
     * @return {@code true} if the patch was applied, {@code false} on hash mismatch
     * @throws IOException          if the file cannot be read or written
     * @throws PatchFailedException if the patch cannot be applied to the file content
     */
    boolean applyPatch(@NonNull Serializable sessionId, @NonNull Serializable pId,
                       @NonNull com.summit.harnesscore.runtime.Workspace workspace) throws IOException, PatchFailedException;
}
