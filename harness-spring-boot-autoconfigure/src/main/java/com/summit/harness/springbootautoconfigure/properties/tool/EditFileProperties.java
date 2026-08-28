package com.summit.harness.springbootautoconfigure.properties.tool;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
@Data
@ConfigurationProperties(prefix = "lingxi.agent.runtime.tool.edit-file")
public class EditFileProperties {
    private boolean enabled;
    private Integer maxOutput;
    private Long timeout;
}
