package com.summit.core.skill;


import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

public class SkillScope implements AutoCloseable{
    private final Set<String> disclosed = new HashSet<>();
    public static final SkillScope EMPTY = new SkillScope();



    public void disclose(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return;
        }
        names.forEach(name -> {
            if (name != null ) {
                disclosed.add(name);
            }
        });
    }
    @Override
    public void close() throws Exception {
        disclosed.clear();
    }
}
