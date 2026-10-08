package com.summit.core.skill;

import java.nio.file.Path;

/**
 * resolve the `SKILL.md` and extract the name and the description in the file
 * <h2>NOTICE</h2>
 * <li>the default resolver depend on the third codebase named `commonMark`.you must add it to your project If you want to use the default resolver</li>
 */
public interface SkillResolver {
    SkillResume resolve(Path skillMD);
}
