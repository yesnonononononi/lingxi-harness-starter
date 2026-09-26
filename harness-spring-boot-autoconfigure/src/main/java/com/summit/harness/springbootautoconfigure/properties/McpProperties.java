package com.summit.harness.springbootautoconfigure.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Startup-only MCP connections. Headers may contain credentials and must not be logged. */
@Getter
@Setter
@ConfigurationProperties("lingxi.mcp")
public class McpProperties {
    private boolean enabled;
    private Map<String, Server> servers = new LinkedHashMap<>();

    @Getter
    @Setter
    public static class Server {
        private boolean enabled = true;
        /** Only streamable-http is currently supported. */
        private String transport = "streamable-http";
        private String url;
        private Map<String, String> headers = new LinkedHashMap<>();
        /** Defaults to the server key followed by an underscore. */
        private String toolNamePrefix;
        private Duration initializationTimeout = Duration.ofSeconds(15);
        /** Timeout of the SDK call; the runtime adds five seconds as an outer guard. */
        private Duration executionTimeout = Duration.ofSeconds(60);
        private int maxOutput = 20_000;
    }
}
