package com.summit.core.agent.abilities;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AbilityProvider {
    private final Set<Ability<?>> abilitySet = new HashSet<>();

    public AbilityProvider(List<Ability<?>> abilityList) {
        this.abilitySet.addAll(abilityList);
    }

    public void add(Ability<?> ability){
        synchronized (this) {
            if(this.abilitySet.contains(ability)){
                throw new RuntimeException("Duplicated ability" + ability.name());
            }
            this.abilitySet.add(ability);
        }
    }


    public  <T extends Ability<?>> T provide(Class<T> type){
        for (Ability<?> ability : abilitySet) {
            if(type.isInstance(ability)){
                return type.cast(ability);
            }
        }
        return null;
    }
}
