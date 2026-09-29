package com.summit.kernel.tools.search;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Settings of the framework's tool-lookup entry.
 *
 * <p>On by default: without it a request that declares MCP servers publishes résumés of remote tools
 * it can never unlock, so the feature would look broken rather than merely off. Applications that
 * would rather not expose the entry turn it off explicitly.</p>
 */
@Data
@ConfigurationProperties(prefix = "lingxi.agent.runtime.tool.search-tool")
public class SearchToolProperties {

    /** Whether the {@code search_tool} lookup entry is registered at all. */
    private boolean enabled = true;

    /**
     * Hits returned by one search.
     *
     * <p>A lookup is the one call whose output size the model cannot predict, so it is bounded here
     * rather than left to the tool's output truncation: a broad keyword would otherwise answer with
     * every tool in the run and crowd out the round that was supposed to use the answer. The true
     * total is still reported, so the model knows to narrow the keyword instead of assuming it saw
     * everything.</p>
     */
    private int maxMatches = 30;

    /**
     * How long one search may run before the runtime reports a timeout to the model. Searches only
     * read already-discovered definitions, so this is a guard against a pathological registry, not a
     * budget a healthy call would ever approach.
     */
    private Duration timeout = Duration.ofSeconds(10);
}
