package com.summit.harness.springbootautoconfigure.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "lingxi.agent.sandbox.cleanup")
public class WorkspaceCleanupProperties {
    private Duration interval = Duration.ofSeconds(30);
    private Duration maxBackoff = Duration.ofMinutes(5);
    private int maxAttempts = 10;
}
