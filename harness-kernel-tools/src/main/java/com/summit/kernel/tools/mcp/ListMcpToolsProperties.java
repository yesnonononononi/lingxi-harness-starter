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

    /** Tools returned by one listing; the rest are counted but withheld. */
    private int maxTools = 50;

    /** How long one listing may run before the runtime reports a timeout. */
    private Duration timeout = Duration.ofSeconds(10);
}
