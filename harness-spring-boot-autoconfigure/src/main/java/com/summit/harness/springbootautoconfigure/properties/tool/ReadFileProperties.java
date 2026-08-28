package com.summit.harness.springbootautoconfigure.properties.tool;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "lingxi.agent.runtime.tool.read-file")
public class ReadFileProperties {
    private boolean enabled;
    private Integer maxOutput;
    private Long timeout;
}
