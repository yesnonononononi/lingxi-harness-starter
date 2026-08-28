package com.summit.harnesscore.tool;

import com.github.difflib.patch.Patch;
import lombok.Builder;

import java.io.Serializable;

@Builder
public record PatchEntity(
        Serializable id,
        Serializable sessionId,
        Patch<String> patch,
        String filePath,
        String fileContentHash
) {
}
