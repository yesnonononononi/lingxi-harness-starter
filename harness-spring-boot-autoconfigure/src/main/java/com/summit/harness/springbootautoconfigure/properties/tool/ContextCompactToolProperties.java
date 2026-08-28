package com.summit.harness.springbootautoconfigure.properties.tool;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
@Data
@ConfigurationProperties(prefix = "lingxi.agent.runtime.tool.context-compact")
public class ContextCompactToolProperties {
    private boolean enabled = true;
    private double threshold = 0.85;
    private Integer maxOutput;
    private Long timeout;
}
