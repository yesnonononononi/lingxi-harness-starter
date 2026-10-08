package com.summit.core.skill;

import com.summit.core.conf.SkillConfig;

import java.util.List;

public interface SkillLoader {
    SkillResolver provideSkillResolver();
    List<SkillResume> load(SkillConfig skillConfig);
}
