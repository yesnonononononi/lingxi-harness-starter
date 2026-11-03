package com.summit.harness.springbootautoconfigure.properties.agent;

import com.summit.core.model.DefaultModelProviderNames;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/** Independent image model connection and generation defaults for business callers. */
@Data
@ConfigurationProperties(prefix = "lingxi.agent.model.conf.image")
public class AgentImageProperties {
    private String provider = DefaultModelProviderNames.DEFAULT_IMAGE;
    private String baseUrl;
    private String apiKey;
    private String modelName;
    private Duration timeout = Duration.ofMinutes(5);
    private int maxRetries = 3;
    private String quality;
    private String size;
    private Integer count;
    private Map<String, String> customHeaders = Map.of();
}
