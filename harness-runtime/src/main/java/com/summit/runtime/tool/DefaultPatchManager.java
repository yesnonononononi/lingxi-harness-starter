package com.summit.runtime.tool;

import com.github.difflib.patch.Patch;
import com.github.difflib.patch.PatchFailedException;
import com.summit.harnesscore.tool.PatchEntity;
import com.summit.harnesscore.tool.PatchManager;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class DefaultPatchManager implements PatchManager<UUID> {

    private final Map<UUID, PatchEntity<UUID>> patches = new ConcurrentHashMap<>();


    @Override
    public @NonNull UUID generateId() {
        return UUID.randomUUID();
    }


    @Override
    public PatchEntity<UUID> getPatch(@NonNull UUID uuid) {
        return this.requirePatchExist(uuid);
    }


    @Override
    public UUID savePatch(@NonNull PatchEntity<UUID> patchEntity) {
        String path = patchEntity.filePath();

        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("File path cannot be null or empty");
        }
        UUID key = this.generateId();
        this.patches.put(key, patchEntity);
        return key;
    }

    @Override
    public void deletePatch(@NonNull UUID uuid) {
        this.requirePatchExist(uuid);
        this.patches.remove(uuid);
    }

    @Override
    public boolean applyToPatch(@NonNull UUID uuid, Charset charset) throws IOException, PatchFailedException {
        PatchEntity<UUID> patch = this.getPatch(uuid);
        Patch<String> p = patch.patch();

        if(p == null) {
            log.warn("Patch is null for file {}", patch.filePath());
            return false;
        }

        Path path = Path.of(patch.filePath());

        if (Files.notExists(path)) throw new FileNotFoundException("File not found: " + patch.filePath());

        String newContent = Files.readString(path,charset);

        if (patch.fileContentHash().equals(this.hashFile(newContent))) {
            List<String> contentList = Arrays.asList(newContent.split("\\R",-1));

            p.applyToExisting(contentList);
            Files.write(path, contentList,charset);
            this.deletePatch(uuid);
            return true;
        }

        log.warn("File hash mismatch for patch {}", uuid);
        return false;
    }

    @Override
    public boolean applyToPatch(@NonNull UUID uuid) throws IOException, PatchFailedException {
        return this.applyToPatch(uuid, StandardCharsets.UTF_8);
    }

    private PatchEntity<UUID> requirePatchExist(UUID uuid) {
        PatchEntity<UUID> patch = patches.get(uuid);

        if (patch == null) {
            throw new IllegalArgumentException(
                    "Patch does not exist: " + uuid
            );
        }

        return patch;
    }
}
