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
 * Registers the context-compaction tool, and publishes the settings that decide when and how hard
 * the runtime compacts.
 *
 * <p>A framework built-in: compaction is a property of running a loop against a finite context, not
 * a capability the application decides to hand the model. It is on unless explicitly switched off,
 * and {@code @ConditionalOnMissingBean} keeps it replaceable.</p>
 *
 * <p>Both beans are declared unconditionally, unlike the tool. The ratios are loop behaviour — the
 * checkpoint summarizes and prunes whether or not the model was ever given a tool to call — so
 * gating them on {@code enabled} would silently turn compaction off for a deployment that only
 * wanted to hide the tool.</p>
 *
 * <p>The two beans exist because this module owns the properties while
 * {@code harness-spring-boot-autoconfigure} owns the {@link AgentConfig} bean, and the second must
 * not depend on the first. Publishing plain values keeps the arrow pointing down: this module knows
 * about the runtime, the auto configuration only knows about the two small types it consumes.</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(ContextCompactToolProperties.class)
public class ContextCompactToolAutoConfiguration {

    /**
     * The ratio that opens the summarize band.
     *
     * @param properties the compaction settings
     * @return the squeeze policy read by the boundary checker
     */
    @Bean
    @ConditionalOnMissingBean
    public AgentConfig.ProgressiveSqueezePolicy progressiveSqueezePolicy(ContextCompactToolProperties properties) {
        return new AgentConfig.ProgressiveSqueezePolicy(
                new AgentConfig.ModelSqueeze(properties.getModelThreshold()));
    }

    /**
     * The summarize-attempt setting handed to {@link AgentConfig}.
     *
     * @param properties the compaction settings
     * @return the tuning values; {@code null} fields mean "use the runtime default"
     */
    @Bean
    @ConditionalOnMissingBean
    public AgentConfig.CompactionTuning compactionTuning(ContextCompactToolProperties properties) {
        return new AgentConfig.CompactionTuning(properties.getMaxSummaryAttempts());
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
