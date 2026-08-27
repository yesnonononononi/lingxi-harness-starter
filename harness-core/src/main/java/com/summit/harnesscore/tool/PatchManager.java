package com.summit.harnesscore.tool;


import com.github.difflib.patch.PatchFailedException;
import lombok.NonNull;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public interface PatchManager<ID> {
    @NonNull
    ID generateId();

    default @NonNull String hashFile(String fileContent) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            byte[] hash =
                    digest.digest(fileContent.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(hash);

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    ;

    PatchEntity<ID> getPatch(@NonNull ID id);

    ID savePatch(@NonNull PatchEntity<ID> patchEntity);

    void deletePatch(@NonNull ID id);

    boolean applyToPatch(@NonNull ID id, Charset charset ) throws IOException, PatchFailedException;

    boolean applyToPatch(@NonNull ID id) throws IOException, PatchFailedException;
}
