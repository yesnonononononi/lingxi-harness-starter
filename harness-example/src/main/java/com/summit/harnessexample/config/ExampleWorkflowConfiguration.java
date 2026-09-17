package com.summit.harnessexample.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.conversation.event.ExplicitUserMeanEvent;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.event.WaitCommandCheckEvent;
import com.summit.core.runtime.LoopSuspender;
import com.summit.core.runtime.SuspensionDecision;
import com.summit.core.runtime.SuspensionRequest;
import com.summit.core.runtime.ShellType;
import com.summit.core.tool.CommandConfirmLevel;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecution;
import com.summit.core.tool.ToolExecutionPolicy;
import com.summit.core.tool.ToolExecutor;
import com.summit.tools.arguments.ExecuteCommandRequest;
import com.summit.tools.terminal.CommandGuard;
import com.summit.tools.terminal.CommandToolDefinitionExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Business-side workflow of this example, built exclusively on the framework's generic SPI.
 *
 * <p>The framework owns the mechanisms — {@link LoopSuspender} to block the loop until a human
 * decides, {@link ToolExecutionPolicy} to run before the tool's own timeout starts,
 * {@link RuntimeEventPublisher} to announce the pending decision. Everything that is
 * product-specific lives here:</p>
 * <ul>
 *   <li>the {@code require_choice} tool and the payload of a question (question + suggested
 *       options),</li>
 *   <li>which commands require human approval and how the decision is interpreted,</li>
 *   <li>the notification shape pushed to the front-end.</li>
 * </ul>
 *
 * <p>Nothing in this class is needed by an application that does not want human-in-the-loop
 * approval: it is an example of how a business implements that behaviour, not a framework
 * feature.</p>
 */
@Configuration
public class ExampleWorkflowConfiguration {

    /** How long a user gets to answer a question / approve a command before the loop resumes. */
    private static final Duration CHOICE_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration COMMAND_APPROVAL_TIMEOUT = Duration.ofMinutes(10);

    /**
     * {@code require_choice}: the model asks the user a question and the loop blocks until an
     * answer arrives through {@code POST /agent/choices/{toolExecutionId}/decide}.
     */
    @Bean
    public ToolDefinition<ToolExecutor> requireChoiceTool(ObjectMapper mapper, RuntimeEventPublisher events,
                                                          LoopSuspender suspender) {
        ToolExecutor executor = execution -> {
            try {
                var json = mapper.readTree(execution.getArgs());
                String question = json.path("question").asText();
                List<String> choices = mapper.convertValue(json.path("choice"),
                        mapper.getTypeFactory().constructCollectionType(List.class, String.class));
                SuspensionRequest request = new SuspensionRequest(execution.getId(), execution.getTurnId(),
                        execution.getSessionId(), "choice",
                        Map.of("question", question, "choices", choices), CHOICE_TIMEOUT);
                SuspensionDecision decision = suspender.suspend(request, ignored ->
                        events.onExplicitUserMean(new ExplicitUserMeanEvent(execution.getTurnId(),
                                execution.getId(), String.valueOf(execution.getSessionId()), question,
                                choices, true, Instant.now())));
                if (decision.status() != SuspensionDecision.Status.RESUMED) {
                    return ToolExecuteResult.err(
                            "user choice was not provided: " + decision.status());
                }
                return ToolExecuteResult.success(
                        String.valueOf(decision.payload().getOrDefault("answer", "")));
            } catch (Exception e) {
                return ToolExecuteResult.err(e.getMessage());
            }
        };
        return ToolDefinition.<ToolExecutor>builder()
                .id("require_choice").name("require_choice").executor(executor).readOnly(true)
                .description("""
                        Ask the user a question and wait for their explicit answer before continuing.
                        Call it BEFORE doing the work whenever the request leaves a decision open that
                        only the user can make — technology or library choice, scope, priority,
                        naming, data loss or other irreversible effects — instead of guessing.
                        Provide 1-6 short suggested options covering the plausible answers; the user
                        may also reply with custom text of their own, so the options are a
                        recommendation rather than a whitelist.
                        Do not use it for anything you can find out yourself with the available tools,
                        and do not ask more than one question at a time.
                        Right after this call the runtime suspends you and waits for the answer: it
                        comes back as the tool result, so continue from there.""")
                .parametersJsonSchema("""
                        {"type":"object","properties":{"question":{"type":"string"},
                        "choice":{"type":"array","items":{"type":"string"}}},
                        "required":["question","choice"]}
                        """).maxOutput(1_000).timeout(0L).build();
    }

    /**
     * Admission policy of the terminal tool: a command that needs confirmation is suspended
     * <em>before</em> {@code executeTool} starts its own timeout, so the human has the full
     * approval window and the command still gets its untouched execution budget afterwards.
     */
    @Bean
    public ToolExecutionPolicy commandApprovalPolicy(ObjectMapper mapper, RuntimeEventPublisher events,
                                                     LoopSuspender suspender) {
        return new ToolExecutionPolicy() {
            @Override
            public ToolExecuteResult beforeExecution(ToolExecution execution) {
                if (execution.getToolDefinition() == null
                        || !(execution.getToolDefinition().executor() instanceof CommandToolDefinitionExecutor)) {
                    return null;
                }
                try {
                    String command = mapper.readValue(execution.getArgs(), ExecuteCommandRequest.class).getCommand();
                    if (!requiresApproval(execution, command)) {
                        return null;
                    }
                    SuspensionDecision decision = suspender.suspend(new SuspensionRequest(execution.getId(),
                                    execution.getTurnId(), execution.getSessionId(), "command.approval",
                                    Map.of("command", command), COMMAND_APPROVAL_TIMEOUT),
                            ignored -> events.onCommandCheck(new WaitCommandCheckEvent(execution.getTurnId(),
                                    execution.getId(), String.valueOf(execution.getSessionId()), command)));
                    if (decision.status() == SuspensionDecision.Status.RESUMED
                            && Boolean.TRUE.equals(decision.payload().get("approved"))) {
                        return null;
                    }
                    return ToolExecuteResult.err(
                            "command was not approved and was not executed");
                } catch (Exception ignored) {
                    return null;
                }
            }

            /** Full access never asks; otherwise the confirm level and the command guard decide. */
            private boolean requiresApproval(ToolExecution execution, String command) {
                CommandConfirmLevel level = execution.getCommandConfirmLevel();
                if (level == null || level == CommandConfirmLevel.FULL_ACCESS) {
                    return false;
                }
                if (level == CommandConfirmLevel.PRE_EXEC_CONFIRM) {
                    return true;
                }
                ShellType shell = execution.getWorkspace() == null ? null
                        : execution.getWorkspace().runtimeEnvironment().shellType();
                return !CommandGuard.isAllowed(command, shell);
            }
        };
    }
}
