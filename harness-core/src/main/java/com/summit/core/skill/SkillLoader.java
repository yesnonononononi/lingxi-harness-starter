package com.summit.core.skill;

public interface SkillLoader {
    default String loadById(String id){
        return "";
    };
    String loadByName(String name);
}
