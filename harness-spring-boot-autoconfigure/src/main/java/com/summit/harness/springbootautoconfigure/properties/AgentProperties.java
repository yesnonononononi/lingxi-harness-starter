package com.summit.harness.springbootautoconfigure.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
@Data
@ConfigurationProperties(prefix = "lingxi.agent.model.conf.stream")
public class AgentProperties {
    private String baseUrl;
    private  String apiKey;
    private String modelName;

}
