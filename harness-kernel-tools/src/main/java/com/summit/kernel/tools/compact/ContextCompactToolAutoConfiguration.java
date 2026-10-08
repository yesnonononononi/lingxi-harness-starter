package com.summit.kernel.tools.compact;

import com.summit.core.tool.ConcurrentPolicy;
import com.summit.core.tool.ToolDefinition;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.compact.DefaultModelCompacter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers the context-compaction tool, and publishes the multi-stage thresholds that decide when
 * the runtime compacts.
 *
 * <p>A framework built-in: compaction is a property of running a loop against a finite context, not
 * a capability the application decides to hand the model. It is on unless explicitly switched off,
 * and {@code @ConditionalOnMissingBean} keeps it replaceable.</p>
 *
 * <p>The policy bean is declared unconditionally, unlike the tool. The two stages of the squeeze are
 * loop behaviour — they truncate and summarize whether or not the model was ever given a tool to
 * call — so gating the thresholds on {@code enabled} would silently turn the bands off for a
 * deployment that only wanted to hide the tool.</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(ContextCompactToolProperties.class)
public class ContextCompactToolAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AgentConfig.ProgressiveSqueezePolicy progressiveSqueezePolicy(ContextCompactToolProperties properties) {
        return new AgentConfig.ProgressiveSqueezePolicy(
                new AgentConfig.OriginalSqueeze(properties.getTruncateThreshold(), properties.getTruncateRounds()),
                new AgentConfig.ModelSqueeze(properties.getModelThreshold()));
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "lingxi.agent.runtime.tool.context-compact",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true
    )
    @ConditionalOnMissingBean(name = "contextCompactToolDefinition")
    public ToolDefinition<DefaultModelCompacter> contextCompactToolDefinition(DefaultModelCompacter compacter) {
        String name = "compact_context";
        return ToolDefinition.<DefaultModelCompacter>builder()
                .executor(compacter)
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
