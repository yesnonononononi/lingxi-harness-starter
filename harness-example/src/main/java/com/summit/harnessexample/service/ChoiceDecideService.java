package com.summit.harnessexample.service;

import com.summit.core.tool.ChoiceDecideGate;
import com.summit.core.tool.DecideRegistry;
import com.summit.harnessexample.common.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Records the user's explicit choice for a waiting {@code require_choice} tool call.
 *
 * <p>Writing the choice into the registry wakes the agent-loop thread blocked at its
 * {@link ChoiceDecideGate}; an already-decided or unknown tool call is reported as a
 * conflict / not-found so the front-end can drop its stale choice card.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChoiceDecideService {

    private final DecideRegistry<ChoiceDecideGate, String> choiceDecideRegistry;

    public Map<String, Object> decide(String toolExecutionId, String choice) {
        ChoiceDecideGate gate = choiceDecideRegistry.get(toolExecutionId);
        if (gate == null) {
            throw ApiException.notFound("no pending choice found for toolExecutionId: " + toolExecutionId,
                    Map.of("toolExecutionId", toolExecutionId, "pendingChoices", choiceDecideRegistry.size()));
        }
        if (!gate.isPending()) {
            throw ApiException.conflict("choice already decided: " + gate.getDecision(),
                    Map.of("toolExecutionId", toolExecutionId,
                            "decision", String.valueOf(gate.getDecision())));
        }
        if (choice == null || choice.isBlank()) {
            throw ApiException.badRequest("choice must not be blank");
        }
        List<String> choices = gate.getChoice() == null || gate.getChoice().choices() == null
                ? List.of()
                : gate.getChoice().choices();
        if (!choices.isEmpty() && !choices.contains(choice)) {
            throw ApiException.badRequest("choice must be one of " + choices + " but was: " + choice);
        }
        boolean applied = choiceDecideRegistry.decide(toolExecutionId, choice);
        if (!applied) {
            throw ApiException.conflict("choice decision failed, it may have already been decided",
                    Map.of("toolExecutionId", toolExecutionId));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("toolExecutionId", toolExecutionId);
        data.put("question", gate.getChoice() == null ? null : gate.getChoice().question());
        data.put("choice", choice);
        data.put("pendingChoices", choiceDecideRegistry.size());
        log.info("【explicit-decision】choice recorded: toolExecutionId={}, choice={}", toolExecutionId, choice);
        return data;
    }
}
