package com.summit.harness.springbootautoconfigure.properties.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "lingxi.agent.model.conf.chat")
public class AgentChatProperties {
    /** Provider used when an AgentRequest does not select one explicitly. */
    private String provider;
    private String baseUrl;
    private  String apiKey;
    private String modelName;
    private Integer maxIterations = 50;
    private int maxTokens = 102400;
    /** Reasoning effort interpreted by the selected provider. */
    private String reasoningEffort = "low";
    private String thinkingType;
    private Integer thinkingBudgetTokens;
    private boolean returnThinking = true;
    private boolean sendThinking = true;
    /**
     * Whether Anthropic may cache the system prompt and tool definitions across requests.
     *
     * <p>Anthropic only reuses a prompt prefix that the request marks with {@code cache_control};
     * with no marker the provider reports zero cache reads however often the prefix repeats, which
     * is what a caller sees as "the cache never hits". A write costs 1.25x and a read 0.1x, so the
     * marker pays off from the second request that shares the prefix.</p>
     *
     * <p>Maps to lingxi.agent.model.conf.chat.prompt-cache</p>
     */
    private boolean promptCache = true;
    /**
     * How long Anthropic keeps a cached prefix: {@code 5m} (default) or {@code 1h}.
     *
     * <p>Applies to every cache breakpoint, and a cache hit refreshes it. The one-hour cache costs
     * more to write, so it is only worth it when requests sharing the prefix are more than five
     * minutes apart.</p>
     *
     * <p>Maps to lingxi.agent.model.conf.chat.prompt-cache-ttl</p>
     */
    private String promptCacheTtl;
    /**
     * Whether Anthropic may cache the whole conversation and move the breakpoint forward as it grows.
     *
     * <p>An agent loop re-sends an ever longer prefix, so this turns the earlier rounds into cache
     * reads instead of reprocessing them. It only pays off while the head of the conversation stays
     * byte-identical: a request whose beginning changes every round pays the write price and reads
     * nothing back, which costs more than not caching at all.</p>
     *
     * <p>Maps to lingxi.agent.model.conf.chat.prompt-cache-conversation</p>
     */
    private boolean promptCacheConversation;
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
