package com.summit.harnessexample.service;

import com.summit.core.tool.CommandConfirmGate;
import com.summit.core.tool.CommandConfirmRegistry;
import com.summit.core.tool.CommandDecision;
import com.summit.harnessexample.common.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records the human decision on a command that is waiting for approval.
 *
 * <p>Writing the decision into the registry wakes the agent-loop thread blocked at its
 * gate; an already-decided or unknown command is reported as a conflict / not-found so
 * the front-end can drop its stale approval card.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommandApprovalService {

    private final CommandConfirmRegistry commandConfirmRegistry;

    public Map<String, Object> decide(String toolExecutionId, CommandDecision decision) {
        CommandConfirmGate gate = commandConfirmRegistry.get(toolExecutionId);
        if (gate == null) {
            throw ApiException.notFound("no pending command found for toolExecutionId: " + toolExecutionId,
                    Map.of("toolExecutionId", toolExecutionId, "pendingCommands", commandConfirmRegistry.size()));
        }
        if (!gate.isPending()) {
            throw ApiException.conflict("command already decided: " + gate.getDecision(),
                    Map.of("toolExecutionId", toolExecutionId,
                            "command", gate.getCommand(),
                            "decision", String.valueOf(gate.getDecision())));
        }
        boolean applied = commandConfirmRegistry.decide(toolExecutionId, decision);
        if (!applied) {
            throw ApiException.conflict("command decision failed, it may have already been decided",
                    Map.of("toolExecutionId", toolExecutionId));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("toolExecutionId", toolExecutionId);
        data.put("command", gate.getCommand());
        data.put("decision", decision.name());
        data.put("pendingCommands", commandConfirmRegistry.size());
        log.info("【command-decision】{} command: toolExecutionId={}, command={}",
                decision, toolExecutionId, gate.getCommand());
        return data;
    }
}
