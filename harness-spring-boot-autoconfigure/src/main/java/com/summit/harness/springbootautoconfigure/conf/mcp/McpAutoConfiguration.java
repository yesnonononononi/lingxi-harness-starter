package com.summit.harness.springbootautoconfigure.conf.mcp;

import com.summit.adapter.langchain4j.mcp.McpClientFactory;
import com.summit.core.mcp.McpProvider;
import com.summit.core.tool.ToolRegistry;
import com.summit.harness.springbootautoconfigure.conf.tool.CommonToolAutoConfiguration;
import com.summit.harness.springbootautoconfigure.properties.McpProperties;
import dev.langchain4j.mcp.client.McpClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

@AutoConfiguration(after = CommonToolAutoConfiguration.class)
@ConditionalOnClass(McpClient.class)
@ConditionalOnProperty(prefix = "lingxi.mcp", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(McpProperties.class)
public class McpAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public McpClientFactory mcpClientFactory() {
        return new McpClientFactory();
    }

    @Bean(destroyMethod = "close")
    public ConfiguredMcpProvider configuredMcpProvider(McpProperties properties, McpClientFactory factory) {
        return new ConfiguredMcpProvider(properties, factory);
    }

    @Bean
    public McpToolRegistrar mcpToolRegistrar(ToolRegistry registry, List<McpProvider> providers) {
        return new McpToolRegistrar(registry, providers);
    }
}
