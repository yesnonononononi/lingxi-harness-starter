package com.summit.harness.springbootautoconfigure.properties.tool;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Objects;

@ConfigurationProperties(prefix = "lingxi.tool")
public record ToolProperties(
        Integer concurrentToolLimit
) {
    public ToolProperties(Integer concurrentToolLimit) {
        this.concurrentToolLimit = Objects.requireNonNullElse(concurrentToolLimit, 5);
    }

}
