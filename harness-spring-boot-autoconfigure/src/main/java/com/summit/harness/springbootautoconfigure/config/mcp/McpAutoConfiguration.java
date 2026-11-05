package com.summit.harness.springbootautoconfigure.config.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.mcp.ScopeMcpProvider;
import com.summit.harness.springbootautoconfigure.config.tool.CommonToolAutoConfiguration;
import io.modelcontextprotocol.client.McpSyncClient;
import mcp.AgentScopeMcpProvider;
import mcp.McpClientSettings;
import mcp.McpClientTransportBuilder;
import mcp.McpSessionFactory;
import mcp.transport.McpHttpTransportBuilder;
import mcp.transport.McpStdioTransportBuilder;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wires the MCP client infrastructure.
 *
 * <p>Nothing here opens a connection: the factory holds no session until a request declares a
 * server, so starting the application — or having a stored configuration that is currently
 * unreachable — never fails the context. The retired {@code lingxi.mcp.enabled} flag keeps having no
 * effect for the same reason: there is no connection to switch off, and the infrastructure beans are
 * inert.</p>
 */
@AutoConfiguration(after = CommonToolAutoConfiguration.class)
@ConditionalOnClass(McpSyncClient.class)
public class McpAutoConfiguration {

    /** Name of the health-check scheduler bean, so applications can replace or inspect it. */
    public static final String HEALTH_SCHEDULER_BEAN = "mcpHealthScheduler";

    // Application serializers for identifiers must not change the types of MCP protocol values.
    private final ObjectMapper mcpJson = new ObjectMapper();

    @Bean
    @ConditionalOnMissingBean
    public McpClientSettings mcpClientSettings() {
        return McpClientSettings.defaults();
    }

    /**
     * Single daemon thread shared by every connection's health check. Daemon on purpose: an idle JVM
     * that keeps a broken server must still be able to exit. Shut down by the container when the
     * context closes, after the factory closed the connections that scheduled work on it.
     */
    @Bean(name = HEALTH_SCHEDULER_BEAN, destroyMethod = "shutdownNow")
    @ConditionalOnMissingBean(name = HEALTH_SCHEDULER_BEAN)
    public ScheduledExecutorService mcpHealthScheduler() {
        AtomicInteger counter = new AtomicInteger();
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mcp-health-check-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * The default transports, as one replaceable list: an application that needs a proxy or another
     * HTTP client publishes its own builders and none of these is used.
     */
    @Bean
    @ConditionalOnMissingBean
    public List<McpClientTransportBuilder> mcpTransportBuilders() {
        return List.of(new McpHttpTransportBuilder(mcpJson), new McpStdioTransportBuilder(mcpJson));
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public McpSessionFactory mcpSessionFactory(McpClientSettings mcpClientSettings,
                                              List<McpClientTransportBuilder> mcpTransportBuilders,
                                              ScheduledExecutorService mcpHealthScheduler) {
        return new McpSessionFactory(mcpClientSettings, mcpTransportBuilders, mcpHealthScheduler, mcpJson);
    }

    @Bean
    @ConditionalOnMissingBean(ScopeMcpProvider.class)
    public ScopeMcpProvider mcpProvider(McpSessionFactory mcpSessionFactory) {
        return new AgentScopeMcpProvider(mcpSessionFactory);
    }
}
