package com.summit.harness.springbootautoconfigure.config;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurationMetadataTest {

    @Test
    void publishesAgentConfigurationMetadata() throws Exception {
        try (InputStream stream = getClass().getClassLoader()
                .getResourceAsStream("META-INF/spring-configuration-metadata.json")) {
            assertNotNull(stream, "Spring configuration metadata must be packaged");
            String metadata = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(metadata.contains("lingxi.agent.model.conf.chat.max-iterations"));
            assertTrue(metadata.contains("lingxi.agent.model.conf.chat.model-name"));
            assertTrue(metadata.contains("lingxi.agent.model.conf.compact.model-name"));
            // The kernel tools moved to harness-kernel-tools and publish their own metadata; their
            // keys are asserted there, by the module that owns them.
            assertTrue(!metadata.contains("lingxi.agent.runtime.tool.context-compact.enabled"));
            assertTrue(!metadata.contains("lingxi.agent.runtime.tool.search-tool.enabled"));
            assertTrue(!metadata.contains("lingxi.agent.execution.distributed-enabled"));
            assertTrue(!metadata.contains("lingxi.agent.runtime.tool.read-file.enabled"));
            assertTrue(!metadata.contains("lingxi.agent.runtime.tool.terminal.enabled"));
        }
    }
}
