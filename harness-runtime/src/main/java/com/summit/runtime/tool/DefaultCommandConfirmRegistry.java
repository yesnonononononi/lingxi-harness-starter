package com.summit.runtime.tool;

import com.summit.core.tool.CommandConfirmGate;
import com.summit.core.tool.CommandConfirmRegistry;
import com.summit.core.tool.CommandDecision;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Default command-approval registry: maps a toolExecutionId to its approval gate.
 */
public class DefaultCommandConfirmRegistry implements CommandConfirmRegistry {

    private final ConcurrentMap<String, CommandConfirmGate> gates = new ConcurrentHashMap<>();

    @Override
    public CommandConfirmGate register(String toolExecutionId, String command) {
        return gates.computeIfAbsent(toolExecutionId, id -> new CommandConfirmGate(id, command));
    }

    @Override
    public CommandConfirmGate get(String toolExecutionId) {
        return gates.get(toolExecutionId);
    }

    @Override
    public boolean decide(String toolExecutionId, CommandDecision decision) {
        CommandConfirmGate gate = gates.get(toolExecutionId);
        return gate != null && gate.decide(decision);
    }

    @Override
    public void unregister(String toolExecutionId) {
        gates.remove(toolExecutionId);
    }

    @Override
    public int size() {
        return gates.size();
    }
}
