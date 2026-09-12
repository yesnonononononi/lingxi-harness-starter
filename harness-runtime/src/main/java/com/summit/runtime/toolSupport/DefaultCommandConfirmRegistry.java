package com.summit.runtime.toolSupport;

import com.summit.core.tool.CommandConfirmGate;
import com.summit.core.tool.CommandDecision;
import com.summit.core.tool.DecideRegistry;
import org.jspecify.annotations.NonNull;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Default command-approval registry: maps a toolExecutionId to its approval gate.
 */
public class DefaultCommandConfirmRegistry implements DecideRegistry<CommandConfirmGate,CommandDecision> {

    private final ConcurrentMap<String, CommandConfirmGate> gates = new ConcurrentHashMap<>();



    @Override
    public CommandConfirmGate register(@NonNull String toolExecutionId, @NonNull CommandConfirmGate decide) {
        return gates.put(toolExecutionId,decide);
    }

    @Override
    public CommandConfirmGate get(@lombok.NonNull String toolExecutionId) {
        return gates.get(toolExecutionId);
    }

    @Override
    public boolean decide(@NonNull String toolExecutionId, @NonNull CommandDecision decision) {
        CommandConfirmGate gate = gates.get(toolExecutionId);
        return gate != null && gate.decide(decision);
    }

    @Override
    public void unregister(@NonNull String toolExecutionId) {
        gates.remove(toolExecutionId);
    }

    @Override
    public int size() {
        return gates.size();
    }
}
