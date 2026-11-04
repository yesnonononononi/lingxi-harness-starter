package com.summit.core.agent.abilities;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "ability")
@JsonSubTypes(
        value = {
                @JsonSubTypes.Type(value = McpAbility.class, name = "mcp"),
                @JsonSubTypes.Type(value = SkillAbility.class, name = "skill"),
                @JsonSubTypes.Type(value = RetryAbility.class, name = "model_retry")
        }
)
@FunctionalInterface
public interface Ability<T> {
    default String name(){
        return "";
    };
     T apply();
}
