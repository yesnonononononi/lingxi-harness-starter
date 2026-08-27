package com.summit.harnesscore.tool;

import com.github.difflib.patch.Patch;
import lombok.Builder;

@Builder
public record PatchEntity<ID>(
        ID id,
        Patch<String> patch,
        String filePath,
        String fileContentHash
) {
}
