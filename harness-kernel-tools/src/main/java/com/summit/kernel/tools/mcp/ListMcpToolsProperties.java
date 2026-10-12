package com.summit.kernel.tools.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Settings of the {@code list_mcp_tools} entry. */
@Data
@ConfigurationProperties(prefix = "lingxi.agent.runtime.tool.list-mcp-tools")
public class ListMcpToolsProperties {

    /** Whether the entry is registered at all. */
    private boolean enabled = true;

    /**
     * Largest window one listing may read. A server holding more tools than this is listed in
     * successive windows; the cap keeps a single call from flooding the context.
     */
    private int maxWindow = 50;

    /**
     * Window one listing reads when the caller names no range. Smaller than {@link #maxWindow} so
     * the common "just show me the inventory" call stays cheap.
     */
    private int defaultWindow = 20;

    /** How long one listing may run before the runtime reports a timeout. */
    private Duration timeout = Duration.ofSeconds(10);
}
