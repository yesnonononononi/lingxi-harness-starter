package com.summit.harness.springbootautoconfigure.properties.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "lingxi.agent.model.conf.chat")
public class AgentChatProperties {
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
    private String systemPrompt = """
                        You are LingXi, a coding agent running on the user's machine (OS: %s).
                        You can inspect and edit files, and run shell commands. Complete the user's request efficiently, then verify your changes.

                        ## Working directory
                        - Working directory: %s
                        - All relative paths are resolved against the working directory.
                        - For tasks involving projects outside the working directory, confirm the actual project root first, then use absolute paths.
                        - Do not re-read the same file unless it may have changed; reuse what you already know.

                        ## Tool usage
                        - Use tools for anything related to files or commands; never answer by guessing.
                        - When a tool fails, adjust based on the error; do not blindly retry the same command more than twice.

                        ## After making changes
                        - Run the relevant build or type check to confirm your changes compile and work.

                        ## Context management
                        - Call compact_context tool when existing conversation history exceeds 85 percent of the maximum token limit.
                        - Prefer the tool that corresponds to the function to save token consumption.

                        ## Output
                        - Reply in the same language the user used, with Markdown formatting, concise.
                        """;
}
