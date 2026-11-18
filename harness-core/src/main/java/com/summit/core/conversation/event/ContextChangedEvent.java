package com.summit.core.conversation.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.summit.core.conversation.ChangedDiff;
import com.summit.core.conversation.message.ContextSectionKind;
import lombok.Builder;
import lombok.Getter;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * This round's context is not the same as the previous round's — reported as an audit fact so the
 * application knows what the framework did to its input, without having to diff the message list.
 *
 * <p>The distinction that matters is {@link #prefixPreserved()}: the framework protects the cached
 * prefix by appending dynamic sections behind it, so "your memory / skill / task changed" costs the
 * tail only. When it is {@code false}, the leading system prompt itself was rewritten and every
 * cached token after it is gone — the one change worth alerting on.</p>
 *
 * <p>Published after the execution's snapshot of this state is durable, so a consumer may treat it
 * as an authoritative fact rather than a hint that something might still be rolled back.</p>
 *
 * <p>The payload does not identify the fact: two revisions can carry the same reasons, the same
 * changed kinds and the same live-block count. {@link #getContextRevision()} is the identity —
 * anything else a consumer derives from the remaining fields only separates the facts by accident,
 * and stops separating them the moment a field is added or dropped.</p>
 */
@Getter
public class ContextChangedEvent extends AbstractEvent {

    /** Classifies what changed; never empty for a published event. */
    private final Set<ContextChangeReason> reasons;

    /**
     * Diff per dynamic section kind, both sides truncated for transport.
     *
     * <p>{@code oldContent} is {@code null} when the section did not exist before (every section of
     * a first round), and the new side is {@code "(none)"} when a section was cleared.</p>
     */
    private final Map<ContextSectionKind, ChangedDiff> changedSections;

    /** Live per-round context blocks in the history after this change. */
    private final int currentLiveBlocks;

    /** How many live blocks this deployment tolerates before they count as piling up. */
    private final int maxLiveBlocks;

    /** Which revision of the execution's context this change created (per-execution, starting at one). */
    private final long contextRevision;

    @Builder
    private ContextChangedEvent(String executionId,
                                Map<String, Object> metaData,
                                long contextRevision,
                                Set<ContextChangeReason> reasons,
                                Map<ContextSectionKind, ChangedDiff> changedSections,
                                int currentLiveBlocks,
                                int maxLiveBlocks) {
        super(executionId, metaData);
        this.contextRevision = contextRevision;

        this.reasons = reasons == null || reasons.isEmpty()
                ? Set.of(ContextChangeReason.DYNAMIC_SECTIONS_CHANGED) : Set.copyOf(reasons);
        this.changedSections = changedSections == null || changedSections.isEmpty()
                ? Map.of() : Collections.unmodifiableMap(new EnumMap<>(changedSections));
        this.currentLiveBlocks = currentLiveBlocks;
        this.maxLiveBlocks = maxLiveBlocks;
    }

    /**
     * Whether the cached prefix survived this change.
     *
     * <p>{@code true} means the framework answered the change by appending behind the prefix: the
     * application's input changed, the cache did not. Derived from {@link #reasons} and therefore
     * always consistent with it.</p>
     */
    @JsonProperty("prefixPreserved")
    public boolean prefixPreserved() {
        return reasons.stream().allMatch(reason -> reason == ContextChangeReason.DYNAMIC_SECTIONS_CHANGED);
    }

    @Override
    public String type() {
        return RuntimeEventType.CONTEXT_CHANGED.type();
    }
}
