package com.summit.harnessexample.service;

import com.summit.core.runtime.LoopSuspender;
import com.summit.core.runtime.SuspensionDecision;
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
 * <p>This is the <b>business</b> half of the user choice: the {@code require_choice} tool and its
 * payload belong to this example, while the framework only supplies the suspension SPI
 * ({@link LoopSuspender}) that blocks the loop and the generic event publisher that announces the
 * pending question to the front-end.</p>
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

    private final LoopSuspender suspender;

    public Map<String, Object> decide(String toolExecutionId, String choice) {
        if (toolExecutionId == null || toolExecutionId.isBlank()) {
            throw ApiException.badRequest("toolExecutionId must not be blank");
        }
        if (choice == null || choice.isBlank()) {
            throw ApiException.badRequest("choice must not be blank");
        }
        String answer = choice.trim();
        if (answer.length() > MAX_ANSWER_LENGTH) {
            throw ApiException.badRequest("choice must not exceed " + MAX_ANSWER_LENGTH
                    + " characters but was: " + answer.length());
        }

        // The pending suspension carries what the model asked: the question and its suggestions.
        LoopSuspender.Suspension pending = suspender.find(toolExecutionId)
                .orElseThrow(() -> ApiException.notFound(
                        "no pending choice found for toolExecutionId: " + toolExecutionId,
                        Map.of("toolExecutionId", toolExecutionId)));
        String question = String.valueOf(pending.request().payload().getOrDefault("question", ""));

        // 候选项只是建议：不在列表里的答案是用户自己的自定义输入，同样接受并交给模型
        boolean offeredOption = isOfferedOption(answer, choicesOf(pending));

        boolean applied = suspender.resolve(toolExecutionId,
                SuspensionDecision.resume(Map.of("answer", answer)));
        if (!applied) {
            throw ApiException.conflict("choice decision failed, it may have already been decided",
                    Map.of("toolExecutionId", toolExecutionId));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("toolExecutionId", toolExecutionId);
        data.put("question", question);
        data.put("choice", answer);
        data.put("offeredOption", offeredOption);
        data.put("custom", !offeredOption);
        log.info("【explicit-decision】choice recorded: toolExecutionId={}, choice={}, offeredOption={}",
                toolExecutionId, answer, offeredOption);
        return data;
    }

    /** Options the model suggested; the suspension payload is the single source of truth for them. */
    private static List<String> choicesOf(LoopSuspender.Suspension pending) {
        Object raw = pending.request().payload().get("choices");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(Objects::nonNull).map(String::valueOf).toList();
    }

    /** Whether the answer equals one of the options offered by the model (surrounding blanks ignored). */
    private static boolean isOfferedOption(String answer, List<String> choices) {
        return choices.stream()
                .map(String::trim)
                .anyMatch(answer::equals);
    }
}
