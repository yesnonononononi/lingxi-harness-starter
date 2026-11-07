package com.summit.core.conf;

import lombok.Builder;
import lombok.Getter;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

import java.util.Collection;
import java.util.HashSet;

@Getter
@SuperBuilder
@Jacksonized
public class ToolRetryConfig extends RetryConfig{
    @Builder.Default
    private final Collection<Throwable> includeExceptions = new HashSet<>();
}