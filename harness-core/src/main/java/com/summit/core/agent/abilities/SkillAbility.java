package com.summit.core.agent.abilities;

import com.summit.core.conf.SkillConfig;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class SkillAbility implements Ability<SkillConfig> {
    private final SkillConfig skillConfig;

    @Override
    public String name() {
        return "SKILL";
    }

    @Override
    public SkillConfig apply() {
        return this.skillConfig;
    }
}
