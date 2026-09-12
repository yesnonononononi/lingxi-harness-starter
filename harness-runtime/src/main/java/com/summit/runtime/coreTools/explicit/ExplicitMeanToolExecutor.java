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

/**
 * Executor of the {@code require_choice} tool: it lets the model ask the user an explicit
 * question with a set of options instead of guessing.
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

            log.info("{} user chose '{}' for toolExecution={}", LOG_PREFIX, decision, toolExecutionId);
            return ToolExecuteResult.success(toolExecutionId, toolExecution.getToolDefinition(),
                    "the user chose: " + decision);
        } catch (JsonProcessingException e) {
            return ToolExecuteResult.err(toolExecutionId, toolExecution.getToolDefinition(),
                    "failed to parse require_choice arguments: " + e);
        }
    }

    private void publishWaitChoice(ToolExecution toolExecution, String question, List<String> choices) {
        this.runtimeEventPublisher.onExplicitUserMean(new ExplicitUserMeanEvent(
                toolExecution.getTurnId(),
                toolExecution.getId(),
                String.valueOf(toolExecution.getSessionId()),
                question,
                choices,
                Instant.now()
        ));
    }

    private RequireArgument resolveArgs(String args) throws JsonProcessingException {
        return objectMapper.readValue(args, RequireArgument.class);
    }
}
