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
     * Neutral fallback only: one sentence stating where the agent runs, plus the environment facts the
     * assembler adds (OS, shell, charset, working directory, isolation, environment variables).
     * Agent identity, application policies and tool preferences belong to the application and
     * are supplied through {@code AgentRequest.systemPrompt}.
     * Placeholders: {@code %s} = reported OS type, {@code %s} = working directory.
     */
    private String systemPrompt = """
                        You are an AI agent running in an assigned workspace (reported OS: %s), working directory: %s.
                        """;
}
