package com.summit.core.conf;

import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

import java.time.Duration;

/** Request-level retry settings contain no executable task or runtime control callbacks. */
@Getter
@Builder
@Jacksonized
public class ModelRetryConfig {
    @Builder.Default
    private final boolean enabled = false;
    @Builder.Default
    private final int maxRetries = 3;
    @Builder.Default
    private final Duration initialDelay = Duration.ofSeconds(10);
    @Builder.Default
    private final int multiplier = 2;
    @Builder.Default
    private final Duration maxDelay = Duration.ofSeconds(10);
}
