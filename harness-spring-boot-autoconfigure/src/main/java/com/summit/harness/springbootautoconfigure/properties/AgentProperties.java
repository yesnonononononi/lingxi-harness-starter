package com.summit.harness.springbootautoconfigure.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "lingxi.agent.model.conf.stream")
public class AgentProperties {
    private String baseUrl;
    private  String apiKey;
    private String modelName;
    /** 连接 + 读超时，默认 60 秒，可用 60s / 5m 等格式配置 */
    private Duration timeout = Duration.ofSeconds(60);

}
