package com.summit.harness.springbootautoconfigure.properties.tool;


import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
@Data
@ConfigurationProperties(prefix = "lingxi.agent.runtime.tool.web-search")
public class WebSearchToolProperties {
    private boolean enabled;
    private String baseUrl;
    private String apiKey;
    private Integer maxOutput;
    private int maxResult = 3;
    private Long timeout;

}
