package com.summit.core.compact;

import java.io.Serializable;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** Supplies application state that must survive context compaction. */
@FunctionalInterface
public interface ContextAttachmentProvider {
    ContextAttachmentProvider NONE = executionId -> Optional.empty();

    Optional<String> attachment(Serializable executionId);

    /** Lower values run first; equal values retain registration order. */
    default int order() {
        return 0;
    }

    /** Resolve fresh application state on every read; provider failures must propagate. */
    static ContextAttachmentProvider compose(List<ContextAttachmentProvider> providers) {
        List<ContextAttachmentProvider> ordered = providers.stream()
                .sorted(Comparator.comparingInt(ContextAttachmentProvider::order)).toList();
        return executionId -> {
            String text = ordered.stream().map(provider -> provider.attachment(executionId))
                    .flatMap(Optional::stream).filter(value -> !value.isBlank())
                    .collect(Collectors.joining("\n\n"));
            return text.isBlank() ? Optional.empty() : Optional.of(text);
        };
    }
}
