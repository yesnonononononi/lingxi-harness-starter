package com.summit.core.conf;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

import java.time.Duration;
@SuperBuilder
@Jacksonized
@Data
public class RetryConfig {
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
