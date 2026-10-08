package com.summit.kernel.tools.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.tool.ConcurrentPolicy;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReadSkillToolAutoConfigurationTest {
    @Test
    void bindsPropertiesAndRegistersDefaultTool() {
        try (AnnotationConfigApplicationContext context = context(Map.of(
                "lingxi.agent.runtime.tool.read-skill.max-output", 1234,
                "lingxi.agent.runtime.tool.read-skill.timeout", "3s"))) {
            context.refresh();
            ToolDefinition<?> definition = context.getBean("readSkillToolDefinition", ToolDefinition.class);
            assertEquals(ReadSkillTool.NAME, definition.name());
            assertEquals(1234, definition.maxOutput());
            assertEquals(3L, definition.timeout());
            assertEquals(ConcurrentPolicy.READ_ONLY, definition.concurrentPolicy());
            assertFalse(definition.executor().requiresWorkspace());
        }
    }

    @Test
    void disablingToolRemovesItsDefinitionAndExecutor() {
        try (AnnotationConfigApplicationContext context = context(Map.of(
                "lingxi.agent.runtime.tool.read-skill.enabled", false))) {
            context.refresh();
            assertFalse(context.containsBean("readSkillToolDefinition"));
            assertTrue(context.getBeansOfType(ReadSkillTool.class).isEmpty());
        }
    }

    @Test
    void applicationDefinitionReplacesDefault() {
        try (AnnotationConfigApplicationContext context = context(Map.of())) {
            ToolDefinition<ToolExecutor> replacement = ToolDefinition.<ToolExecutor>builder()
                    .id(ReadSkillTool.NAME).name(ReadSkillTool.NAME)
                    .executor(call -> ToolExecuteResult.success("business resource"))
                    .maxOutput(999).timeout(1L).build();
            context.registerBean("readSkillToolDefinition", ToolDefinition.class, () -> replacement);
            context.refresh();
            assertSame(replacement, context.getBean("readSkillToolDefinition"));
            assertEquals(1, context.getBeansOfType(ToolDefinition.class).size());
        }
    }

    @Test
    void subsecondTimeoutRetainsADeadlineAndInvalidLimitsFail() {
        ReadSkillToolProperties properties = new ReadSkillToolProperties();
        properties.setTimeout(Duration.ofMillis(100));
        ReadSkillToolAutoConfiguration configuration = new ReadSkillToolAutoConfiguration();
        ReadSkillTool executor = new ReadSkillTool(new ObjectMapper());
        assertEquals(1L, configuration.readSkillToolDefinition(executor, properties).timeout());
        properties.setMaxOutput(0);
        assertThrows(IllegalArgumentException.class, () -> configuration.readSkillToolDefinition(executor, properties));
    }

    @Test
    void autoConfigurationIsPublished() throws Exception {
        try (java.io.InputStream stream = getClass().getClassLoader().getResourceAsStream(
                "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")) {
            assertNotNull(stream);
            assertTrue(new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .contains(ReadSkillToolAutoConfiguration.class.getName()));
        }
    }

    private AnnotationConfigApplicationContext context(Map<String, Object> properties) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        context.register(ReadSkillToolAutoConfiguration.class);
        return context;
    }
}
