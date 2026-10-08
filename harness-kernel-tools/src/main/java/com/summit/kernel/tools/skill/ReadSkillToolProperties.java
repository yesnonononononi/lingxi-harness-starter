package com.summit.kernel.tools.skill;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Settings for the host-side Skill resource reader. */
@Data
@ConfigurationProperties(prefix = "lingxi.agent.runtime.tool.read-skill")
public class ReadSkillToolProperties {
    private boolean enabled = true;
    private int maxOutput = 20_000;
    private Duration timeout = Duration.ofSeconds(10);
}
