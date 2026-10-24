package com.summit.kernel.tools.compact;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Model summarization settings; the local fallback uses the runtime input budget directly. */
@Data
@ConfigurationProperties(prefix = "lingxi.agent.runtime.tool.context-compact")
public class ContextCompactToolProperties {

    /** Whether the model may be given the compact_context tool; checkpoints remain independent. */
    private boolean enabled = true;

    /** Starts summarization before the hard input budget is exhausted. */
    private double modelThreshold = 0.85;

    /** Null means one attempt; non-positive values are rejected when AgentConfig is constructed. */
    private Integer maxSummaryAttempts;
}
