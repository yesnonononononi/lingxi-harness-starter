package com.summit.harness.springbootautoconfigure.config.mcp;

import com.summit.adapter.langchain4j.mcp.McpClientFactory;
import com.summit.adapter.langchain4j.mcp.AgentScopeMcpProvider;
import com.summit.core.mcp.ScopeMcpProvider;
import com.summit.harness.springbootautoconfigure.config.tool.CommonToolAutoConfiguration;
import dev.langchain4j.mcp.client.McpClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = CommonToolAutoConfiguration.class)
@ConditionalOnClass(McpClient.class)
@ConditionalOnProperty(prefix = "lingxi.mcp", name = "enabled", havingValue = "true")
public class McpAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(McpClientFactory.class)
    public McpClientFactory mcpClientFactory() {
        return new McpClientFactory();
    }

    @Bean
    @ConditionalOnMissingBean(ScopeMcpProvider.class)
    public ScopeMcpProvider mcpProvider(McpClientFactory mcpClientFactory) {
        return new AgentScopeMcpProvider(mcpClientFactory);
    }


}
