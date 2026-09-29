package com.summit.kernel.tools;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The kernel tools publish the metadata of the settings they own.
 *
 * <p>Asserted here rather than in the auto-configuration module: these properties moved with the
 * tools, and a key that is documented nowhere is exactly the kind of thing that gets typed wrong in
 * a deployment and silently ignored. The module that owns a property is the module that must prove
 * it is discoverable.</p>
 */
class KernelToolsConfigurationMetadataTest {

    @Test
    void publishesKernelToolMetadata() throws Exception {
        try (InputStream stream = getClass().getClassLoader()
                .getResourceAsStream("META-INF/spring-configuration-metadata.json")) {
            assertNotNull(stream, "Spring configuration metadata must be packaged");
            String metadata = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(metadata.contains("lingxi.agent.runtime.tool.search-tool.enabled"));
            assertTrue(metadata.contains("lingxi.agent.runtime.tool.search-tool.max-matches"));
            assertTrue(metadata.contains("lingxi.agent.runtime.tool.search-tool.timeout"));
            assertTrue(metadata.contains("lingxi.agent.runtime.tool.context-compact.enabled"));
            assertTrue(metadata.contains("lingxi.agent.runtime.tool.context-compact.truncate-threshold"));
            assertTrue(metadata.contains("lingxi.agent.runtime.tool.context-compact.model-threshold"));
            assertTrue(metadata.contains("lingxi.agent.runtime.tool.context-compact.truncate-rounds"));
        }
    }
}
