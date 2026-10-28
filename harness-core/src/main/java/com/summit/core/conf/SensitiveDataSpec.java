package com.summit.core.conf;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;

/** A persisted reference; retaining and resolving immutable revisions belongs to the application. */
@Builder
@AllArgsConstructor
@Data
@Jacksonized
public class SensitiveDataSpec {
    private Number id;
    @Builder.Default
    private int version = 1;
}
