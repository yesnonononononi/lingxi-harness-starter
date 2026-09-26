package com.summit.adapter.langchain4j.mcp;

import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;

import java.time.Duration;
import java.util.Map;

/** Creates owned Streamable HTTP connections. Applications can replace this factory. */
public class McpClientFactory {
    public McpClient create(String key, String url, Map<String, String> headers,
                            Duration initializationTimeout, Duration executionTimeout) {
        var transport = StreamableHttpMcpTransport.builder()
                .url(url)
                .customHeaders(Map.copyOf(headers))
                .logRequests(false)
                .logResponses(false)
                .build();
        try {
            return new DefaultMcpClient.Builder()
                    .key(key)
                    .transport(transport)
                    .initializationTimeout(initializationTimeout)
                    .toolExecutionTimeout(executionTimeout)
                    .build();
        } catch (RuntimeException | Error failure) {
            try {
                transport.close();
            } catch (Exception closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }
}
