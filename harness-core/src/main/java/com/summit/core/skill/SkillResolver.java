package com.summit.core.skill;

import java.nio.file.Path;

/**
 * Resolves {@code SKILL.md} and extracts its name and description.
 * <p>The default resolver requires CommonMark on the classpath.
 */
public interface SkillResolver {
    SkillResume resolve(Path skillMD);
}
