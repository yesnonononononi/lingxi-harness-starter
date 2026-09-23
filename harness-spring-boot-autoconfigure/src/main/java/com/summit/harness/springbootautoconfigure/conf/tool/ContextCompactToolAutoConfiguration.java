package com.summit.harness.springbootautoconfigure.conf.tool;

import com.summit.core.compact.ContextAttachmentProvider;
import com.summit.core.model.chat.ChatModel;
import com.summit.core.tool.ConcurrentPolicy;
import com.summit.core.tool.ToolDefinition;
import com.summit.harness.springbootautoconfigure.properties.tool.ContextCompactToolProperties;
import com.summit.runtime.compact.ContextCompactToolExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(ContextCompactToolProperties.class)
public class ContextCompactToolAutoConfiguration {
    @Bean
    @ConditionalOnProperty(
            prefix = "lingxi.agent.runtime.tool.context-compact",
            name = "enabled",
            havingValue = "true"
    )
    @ConditionalOnMissingBean(name = "contextCompactToolDefinition")
    public ToolDefinition<ContextCompactToolExecutor> contextCompactToolDefinition(@Qualifier("defaultContextCompactModel") ChatModel defaultContextCompactModel, ContextAttachmentProvider contextAttachmentProvider) {
        String name = "compact_context";
        return ToolDefinition.<ContextCompactToolExecutor>builder()
                .executor(new ContextCompactToolExecutor(defaultContextCompactModel, contextAttachmentProvider))
                .concurrentPolicy(ConcurrentPolicy.SERIAL_MUTATION)
                .id(name)
                .name(name)
                .description("""
                        Context compression tool. Call this tool when the conversation has accumulated too much content and you need to summarize the history to free up context.
                        The 'context' parameter is required and must contain the full conversation history (as a JSON string of messages) that needs to be compressed.

                        """)
                .parametersJsonSchema("""
                        {
                          "type": "object",
                          "properties": {
                            "context": {"type": "string", "description": "The full conversation history to compress, as a JSON string. Required parameter."}
                          }
                        }
                        """)
                .maxOutput(500)
                .timeout(30L)

                .build();
    }


}
