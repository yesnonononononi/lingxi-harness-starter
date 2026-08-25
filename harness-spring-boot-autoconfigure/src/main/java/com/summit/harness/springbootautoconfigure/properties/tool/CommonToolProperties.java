package com.summit.harness.springbootautoconfigure.properties.tool;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
@Data
@ConfigurationProperties(prefix = "lingxi.agent.runtime.tool.common")
public class CommonToolProperties {
    private int maxOutput = 500;
    private long timeout = 30;
}
