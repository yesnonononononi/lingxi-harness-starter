package com.summit.runtime.toolSupport;

import com.summit.core.tool.ChoiceDecideGate;

import com.summit.core.tool.DecideRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
@Slf4j
public class DefaultChoiceDecideRegistry implements DecideRegistry<ChoiceDecideGate,String> {
    private final Map<String, ChoiceDecideGate> store = new ConcurrentHashMap<>();

    @Override
    public ChoiceDecideGate register(@NonNull String toolExecutionId, @NonNull ChoiceDecideGate decide) {
        return store.put(toolExecutionId,decide);
    }

    @Override
    public ChoiceDecideGate get(@lombok.NonNull String toolExecutionId) {
        return store.get(toolExecutionId);
    }


    @Override
    public boolean decide(@NonNull String toolExecutionId, @NonNull String decision) {
        ChoiceDecideGate choiceDecideGate = this.get(toolExecutionId);
        if(choiceDecideGate == null)return false;
        choiceDecideGate.decide(decision);
        return true;
    }

    @Override
    public void unregister(@NonNull String toolExecutionId) {
        this.store.remove(toolExecutionId);
    }

    @Override
    public int size() {
        return this.store.size();
    }
}
