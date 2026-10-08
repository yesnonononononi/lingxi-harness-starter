package com.summit.kernel.tools.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.tool.ConcurrentPolicy;
import com.summit.core.tool.ToolDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** A replacement definition keeps the tool name and the name/path argument contract. */
@AutoConfiguration
@EnableConfigurationProperties(ReadSkillToolProperties.class)
@ConditionalOnProperty(prefix = "lingxi.agent.runtime.tool.read-skill", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ReadSkillToolAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(value = ReadSkillTool.class, name = "readSkillToolDefinition")
    public ReadSkillTool readSkillTool(ObjectMapper objectMapper) {
        return new ReadSkillTool(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(name = "readSkillToolDefinition")
    public ToolDefinition<ReadSkillTool> readSkillToolDefinition(ReadSkillTool executor,
                                                               ReadSkillToolProperties properties) {
        if (properties.getMaxOutput() <= 0 || properties.getTimeout() == null
                || properties.getTimeout().isNegative() || properties.getTimeout().isZero()) {
            throw new IllegalArgumentException("Skill tool output limit and timeout must be positive");
        }
        return ToolDefinition.<ReadSkillTool>builder()
                .id(ReadSkillTool.NAME)
                .name(ReadSkillTool.NAME)
                .executor(executor)
                .concurrentPolicy(ConcurrentPolicy.READ_ONLY)
                .description("""
                        Read a Skill entry or one of its referenced text resources. Use the entry path
                        published in the Skill prompt. Resolve references against the resourceDirectory
                        returned for the containing file. Absolute paths refer to host-side Skill
                        resources; relative paths start at this request's configured Skill root.
                        This tool does not execute scripts.
                        """)
                .parametersJsonSchema("""
                        {
                          "type": "object",
                          "properties": {
                            "name": {"type": "string", "description": "Optional Skill name for the returned label."},
                            "path": {"type": "string", "description": "Entry or resource path. Absolute host path, or relative to the configured Skill root."}
                          },
                          "required": ["path"],
                          "additionalProperties": false
                        }
                        """)
                .maxOutput(properties.getMaxOutput())
                .timeout(Math.max(properties.getTimeout().toSeconds(), 1L))
                .build();
    }
}
