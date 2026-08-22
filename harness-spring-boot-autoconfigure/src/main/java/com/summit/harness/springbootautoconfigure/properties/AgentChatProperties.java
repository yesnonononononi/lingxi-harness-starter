package com.summit.harness.springbootautoconfigure.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
@Data
@ConfigurationProperties(prefix = "lingxi.agent.model.conf.chat")
public class AgentChatProperties {
    private String modelName;
    private String baseUrl;
    private String apiKey;

}
