package com.summit.core.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.jackson.Jacksonized;

import java.util.List;

/**
 * Tool admission specification for one request.
 *
 * <p>{@link #toolList} defines the static model-visible whitelist (kept constant to preserve
 * prompt prefix caching), while {@link #forbiddenTools} dynamically blocks execution per round
 * without mutating model tool declarations.</p>
 */
@Data
@Builder
@Jacksonized
@NoArgsConstructor
@AllArgsConstructor
public class ToolSpec {
    /** Optional request-level whitelist of tool names. A {@code null} or empty list exposes no application tools. */
    private List<String> toolList;

    /** Optional per-round blacklist of tool names blocked from execution even when present in {@link #toolList}. */
    @Builder.Default
    private List<String> forbiddenTools = List.of();
}
