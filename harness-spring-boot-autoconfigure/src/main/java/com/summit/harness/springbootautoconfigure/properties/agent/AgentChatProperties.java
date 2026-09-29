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
    private Integer maxIterations = 50;
    private int maxTokens = 102400;
    // `none`, `minimal`, `low`, `medium`, `high`, `xhigh`, `max` a
    private String reasoningEffort = "low";
    private boolean returnThinking = true;
    private boolean sendThinking = true;
    private Duration timeout = Duration.ofSeconds(60);
    /**
     * How many compaction rounds may run back to back before the loop gives up on the run.
     *
     * <p>Compaction is the model asking to summarize its own history through
     * {@code compact_context}. One is healthy; several in a row means the context cannot be brought
     * under budget — the summary is not shrinking, or the work refills it immediately — and letting
     * that continue burns the whole budget without producing an answer. Counted per run, and reset
     * by any round that is not a compaction.
     *
     * <p>Maps to lingxi.agent.model.conf.chat.max-consecutive-compactions</p>
     */
    private int maxConsecutiveCompactions = 3;
    /**
     * How many model rounds pass between two context-usage notifications.
     *
     * <p>Usage is telemetry: it is published for the application's progress display, and publishing
     * it on every round costs a token count of the whole conversation each time. One round of lag is
     * invisible to a user watching a progress bar, so the interval trades a little freshness for a
     * measurable amount of work. A value below one means every round.
     *
     * <p>Maps to lingxi.agent.model.conf.chat.usage-report-interval</p>
     */
    private int usageReportInterval = 5;
}
