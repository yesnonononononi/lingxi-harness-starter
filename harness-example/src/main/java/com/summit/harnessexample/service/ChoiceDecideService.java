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
import java.util.Objects;

/**
 * Records the user's explicit choice for a waiting {@code require_choice} tool call.
 *
 * <p>Writing the choice into the registry wakes the agent-loop thread blocked at its
 * {@link ChoiceDecideGate}; an already-decided or unknown tool call is reported as a
 * conflict / not-found so the front-end can drop its stale choice card.</p>
 *
 * <p>The options proposed by the model are a recommendation, not a whitelist: the user may pick
 * one of them <em>or</em> answer with their own custom text. A custom answer is accepted as the
 * user's decision (only its length is bounded, so an oversized input cannot flood the model
 * context); the response tells the caller which of the two it was.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChoiceDecideService {

    /** Upper bound of a user answer; longer answers are rejected as a bad request. */
    public static final int MAX_ANSWER_LENGTH = 1000;

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
        String answer = choice.trim();
        if (answer.length() > MAX_ANSWER_LENGTH) {
            throw ApiException.badRequest("choice must not exceed " + MAX_ANSWER_LENGTH
                    + " characters but was: " + answer.length());
        }

        // 候选项只是建议：不在列表里的答案是用户自己的自定义输入，同样接受并交给模型
        boolean offeredOption = isOfferedOption(answer, choicesOf(gate));
        boolean applied = choiceDecideRegistry.decide(toolExecutionId, answer);
        if (!applied) {
            throw ApiException.conflict("choice decision failed, it may have already been decided",
                    Map.of("toolExecutionId", toolExecutionId));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("toolExecutionId", toolExecutionId);
        data.put("question", gate.getChoice() == null ? null : gate.getChoice().question());
        data.put("choice", answer);
        data.put("offeredOption", offeredOption);
        data.put("custom", !offeredOption);
        data.put("pendingChoices", choiceDecideRegistry.size());
        log.info("【explicit-decision】choice recorded: toolExecutionId={}, choice={}, offeredOption={}",
                toolExecutionId, answer, offeredOption);
        return data;
    }

    private static List<String> choicesOf(ChoiceDecideGate gate) {
        return gate.getChoice() == null || gate.getChoice().choices() == null
                ? List.of()
                : gate.getChoice().choices();
    }

    /** Whether the answer equals one of the options offered by the model (surrounding blanks ignored). */
    private static boolean isOfferedOption(String answer, List<String> choices) {
        return choices.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .anyMatch(answer::equals);
    }
}
