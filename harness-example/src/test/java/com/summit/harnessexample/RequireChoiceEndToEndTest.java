package com.summit.harnessexample;

import com.summit.core.conversation.event.ExplicitUserMeanEvent;
import com.summit.core.conversation.event.ToolCallEndEvent;
import com.summit.core.runtime.RuntimeListener;
import com.summit.core.runtime.Workspace;
import com.summit.core.tool.ChoiceDecideGate;
import com.summit.core.tool.CommandConfirmLevel;
import com.summit.core.tool.DecideRegistry;
import com.summit.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Real end-to-end verification of the {@code require_choice} (explicit tool) loop against a live
 * model: the user gives a semantically vague instruction, the agent must decide on its own to ask
 * (and generate the options), the loop blocks, the test answers as "the user", and the chosen
 * option must come back to the model as the tool output.
 *
 * <p>Skipped automatically when no model key is configured. Override the instruction with
 * {@code -De2e.prompt=...}.</p>
 */
@SpringBootTest(properties = "lingxi.agent.workspace=local")
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_APIKEY", matches = ".+")
class RequireChoiceEndToEndTest {

    private static final List<ExplicitUserMeanEvent> USER_MEANS = new CopyOnWriteArrayList<>();
    private static final List<ToolCallEndEvent> TOOL_ENDS = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class CaptorConfig {
        @Bean
        RuntimeListener requireChoiceCaptor() {
            return new RuntimeListener() {
                @Override
                public void onExplicitUserMean(ExplicitUserMeanEvent event) {
                    USER_MEANS.add(event);
                }

                @Override
                public void onToolCallOutput(ToolCallEndEvent event) {
                    TOOL_ENDS.add(event);
                }
            };
        }
    }

    @Autowired
    private Demo demo;

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private DecideRegistry<ChoiceDecideGate, String> choiceDecideRegistry;

    @Test
    void theAgentAsksForAnExplicitChoiceAndResumesAfterTheUserDecides() throws Exception {
        USER_MEANS.clear();
        TOOL_ENDS.clear();

        assertNotNull(toolRegistry.getTool("require_choice"), "require_choice must be registered as a tool");
        assertTrue(toolRegistry.getTool("require_choice").readOnly(),
                "require_choice must be read-only so it stays available in both PLANING and EXECUTE");

        String prompt = System.getProperty("e2e.prompt",
                """
                我想给这个项目加一个持续集成(CI)的配置。具体方案我还没想好，有几个关键点需要你拿主意，
                请你先向我确认关键需求，等我确认之后再告诉我执行计划，先不要修改任何文件。
                """);

        Workspace workspace = new LocalWorkSpace();
        Future<?> run = Executors.newSingleThreadExecutor().submit(() -> demo.chat(prompt, false,
                "e2e-require-choice-session", "e2e-require-choice", workspace,
                CommandConfirmLevel.FULL_ACCESS, null, null));

        // stand in for the user: answer every question the agent asks with its first option
        Set<String> answered = ConcurrentHashMap.newKeySet();
        Executors.newSingleThreadExecutor().submit(() -> {
            while (!run.isDone()) {
                for (ExplicitUserMeanEvent ask : USER_MEANS) {
                    if (answered.add(ask.getToolExecutionId()) && !ask.getChoices().isEmpty()) {
                        boolean applied = choiceDecideRegistry.decide(ask.getToolExecutionId(), ask.getChoices().get(0));
                        System.out.println("[E2E] answered toolExecution=" + ask.getToolExecutionId()
                                + " choice=" + ask.getChoices().get(0) + " applied=" + applied);
                    }
                }
                sleep(200);
            }
        });

        // the agent must decide on its own to ask the user, and generate the options itself
        awaitUntil(() -> !USER_MEANS.isEmpty(), 180_000, "the agent never asked the user (require_choice was not called)");
        ExplicitUserMeanEvent ask = USER_MEANS.get(0);
        System.out.println("[E2E] agent asked: " + ask.getQuestion());
        System.out.println("[E2E] agent generated options: " + ask.getChoices());
        assertNotNull(ask.getQuestion(), "the question must not be null");
        assertFalse(ask.getQuestion().isBlank(), "the question must not be blank");
        assertFalse(ask.getChoices().isEmpty(), "the agent must generate selectable options");

        run.get(180, TimeUnit.SECONDS);

        boolean handedBack = TOOL_ENDS.stream()
                .filter(e -> "require_choice".equals(e.getToolName()))
                .anyMatch(e -> e.getOutput() != null && e.getOutput().contains(ask.getChoices().get(0)));
        assertTrue(handedBack, "the user's choice must be fed back to the model as the tool output, got: "
                + TOOL_ENDS.stream().filter(e -> "require_choice".equals(e.getToolName()))
                .map(ToolCallEndEvent::getOutput).toList());
    }

    private static void awaitUntil(BooleanSupplier condition, long timeoutMillis, String message) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail(message);
            }
            sleep(200);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
