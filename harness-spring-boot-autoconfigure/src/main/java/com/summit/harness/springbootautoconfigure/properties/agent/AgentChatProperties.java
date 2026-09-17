package com.summit.harness.springbootautoconfigure.properties.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "lingxi.agent.model.conf.chat")
public class AgentChatProperties {
    /** Provider used when an AgentRequest does not select one explicitly. */
    private String provider = "default";
    private String baseUrl;
    private  String apiKey;
    private String modelName;
    /**
     * Threshold for the local progressive (truncate) squeeze: when the context token ratio reaches this
     * value, the oldest tool rounds start being truncated.
     * Maps to lingxi.agent.model.conf.chat.truncate-squeeze-threshold
     */
    private double truncateSqueezeThreshold = 0.7;
    /**
     * Old tool rounds processed per local squeeze pass (no longer derived from expectTokens).
     * Maps to lingxi.agent.model.conf.chat.expect-truncate-turn
     */
    private int expectTruncateTurn = 5;
    /**
     * Threshold for the model deep compaction (compact_context): when the context token ratio reaches
     * this value, the compact model is expected to summarize the context.
     * Maps to lingxi.agent.model.conf.chat.model-squeeze-threshold
     */
    private double modelSqueezeThreshold = 0.85;
    private Integer maxIterations = 50;
    private int maxTokens = 102400;
    // `none`, `minimal`, `low`, `medium`, `high`, `xhigh`, `max` a
    private String reasoningEffort = "low";
    private boolean returnThinking = true;
    private boolean sendThinking = true;
    private Duration timeout = Duration.ofSeconds(60);
    /**
     * Neutral fallback only. Agent identity, safety policy, approval rules and tool preferences belong
     * to the application and can be supplied through {@code AgentRequest.systemPrompt}.
     */
    private String systemPrompt = """
                        You are an AI agent running in an assigned workspace (reported OS: %s).
                        Working directory: %s
                        Follow the application's instructions and use only the tools exposed to this request.
                        """;
}
