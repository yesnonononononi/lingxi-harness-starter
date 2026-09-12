package com.summit.runtime.coreTools.explicit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.conversation.event.ExplicitUserMeanEvent;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.tool.ChoiceDecideGate;
import com.summit.core.tool.DecideRegistry;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecution;
import com.summit.core.tool.ToolExecutor;
import com.summit.core.tool.ToolResultType;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Executor of the {@code require_choice} tool: it lets the model ask the user an explicit
 * question with a set of options instead of guessing.
 *
 * <p>The options are only a recommendation: the user may always answer with their own
 * free-form text instead of picking one of them. In that case the executor hands the custom
 * answer back to the model, explicitly marked as a custom answer (not as one of the options),
 * so the model follows what the user actually said.</p>
 *
 * <p>It reuses the very same human-in-the-loop mechanics as command approval
 * ({@link ChoiceDecideGate} + {@link DecideRegistry} + the loop-boundary wait inside
 * {@code DefaultToolExecutionManager}):</p>
 * <ol>
 *   <li>no gate yet → registers one, broadcasts {@link ExplicitUserMeanEvent} and
 *       short-circuits with {@link ToolResultType#CHOICE_REQUIRED};</li>
 *   <li>the execution manager waits (bounded, interruptible) at the loop boundary;</li>
 *   <li>an HTTP endpoint writes the selected option into the gate through the registry;</li>
 *   <li>the manager re-executes this executor, which now finds the decision and returns it
 *       as the tool output so the model can continue.</li>
 * </ol>
 */
@AllArgsConstructor
@Slf4j
public class ExplicitMeanToolExecutor implements ToolExecutor {

    private static final String LOG_PREFIX = "【explicit】";

    private final ObjectMapper objectMapper;
    private final RuntimeEventPublisher runtimeEventPublisher;
    private final DecideRegistry<ChoiceDecideGate, String> choiceDecideRegistry;

    @Override
    public @NonNull ToolExecuteResult execute(ToolExecution toolExecution) {
        String toolExecutionId = toolExecution.getId();
        try {
            RequireArgument requireArgument = resolveArgs(toolExecution.getArgs());
            List<String> choices = requireArgument.getChoice() == null ? List.of() : requireArgument.getChoice();

            ChoiceDecideGate gate = this.choiceDecideRegistry.get(toolExecutionId);
            if (gate == null) {
                this.choiceDecideRegistry.register(toolExecutionId, new ChoiceDecideGate(toolExecutionId,
                        new ChoiceDecideGate.Choice(requireArgument.getQuestion(), choices)));
                publishWaitChoice(toolExecution, requireArgument.getQuestion(), choices);
                log.info("{} question suspended for user choice, toolExecution={}, question={}",
                        LOG_PREFIX, toolExecutionId, requireArgument.getQuestion());
                return ToolExecuteResult.err(toolExecutionId, toolExecution.getToolDefinition(),
                        "waiting for the user to choose before the agent can continue: " + requireArgument.getQuestion(),
                        ToolResultType.CHOICE_REQUIRED);
            }

            String decision = gate.getDecision();
            if (decision == null) {
                // Still PENDING but scheduled again (abnormal path): publish idempotently and suspend
                publishWaitChoice(toolExecution, requireArgument.getQuestion(), choices);
                return ToolExecuteResult.err(toolExecutionId, toolExecution.getToolDefinition(),
                        "still waiting for the user to choose: " + requireArgument.getQuestion(),
                        ToolResultType.CHOICE_REQUIRED);
            }

            String answer = decision.trim();
            boolean offeredOption = isOfferedOption(answer, choices);
            log.info("{} user decided '{}' for toolExecution={}, offeredOption={}",
                    LOG_PREFIX, answer, toolExecutionId, offeredOption);
            return ToolExecuteResult.success(toolExecutionId, toolExecution.getToolDefinition(),
                    offeredOption
                            ? "the user chose: " + answer
                            : customAnswerOutput(answer, choices));
        } catch (JsonProcessingException e) {
            return ToolExecuteResult.err(toolExecutionId, toolExecution.getToolDefinition(),
                    "failed to parse require_choice arguments: " + e);
        }
    }

    /** Whether the answer is one of the options offered by the model (surrounding blanks ignored). */
    private static boolean isOfferedOption(String answer, List<String> choices) {
        return choices.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .anyMatch(answer::equals);
    }

    /**
     * The user did not pick any option and typed their own answer: tell the model in plain words
     * that this is a custom answer (the offered options are kept as context) and that it must be
     * treated as the user's explicit decision.
     */
    private static String customAnswerOutput(String answer, List<String> choices) {
        return "the user did not pick any of the offered options and answered with their own custom choice: "
                + answer
                + (choices.isEmpty() ? "" : " (the offered options were: " + choices + ")")
                + ". Treat the custom choice as the user's explicit decision and continue with it.";
    }

    private void publishWaitChoice(ToolExecution toolExecution, String question, List<String> choices) {
        this.runtimeEventPublisher.onExplicitUserMean(new ExplicitUserMeanEvent(
                toolExecution.getTurnId(),
                toolExecution.getId(),
                String.valueOf(toolExecution.getSessionId()),
                question,
                choices,
                // the options are a recommendation only: the front-end may offer an extra
                // free-form input and the user's custom answer is accepted as well
                true,
                Instant.now()
        ));
    }

    private RequireArgument resolveArgs(String args) throws JsonProcessingException {
        return objectMapper.readValue(args, RequireArgument.class);
    }
}
