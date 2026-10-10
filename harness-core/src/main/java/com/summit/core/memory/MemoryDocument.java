package com.summit.core.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Loaded document snapshot; its reference and version identify the target of a later write. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryDocument {
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MemoryItem {
        private String content;
        private String description;
        private String createTime;
    }

    private String reference;

    /** Revision of this snapshot, required for updates to an existing writable document. */
    private Integer version;

    /** Complete stored text used as oldContent when proposing a whole-document replacement. */
    private String content;

    /** Optional annotated view for recall/rendering; these entries are not individual write targets. */
    @Builder.Default
    private List<MemoryItem> lines = List.of();

    public MemoryDocument(List<MemoryItem> lines) {
        this.lines = lines;
    }
}
