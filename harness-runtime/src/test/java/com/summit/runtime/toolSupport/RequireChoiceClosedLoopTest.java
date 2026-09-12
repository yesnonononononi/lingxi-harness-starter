package com.summit.runtime.toolSupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.event.ExplicitUserMeanEvent;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.runtime.OsType;
import com.summit.core.runtime.RuntimeEnvironment;
import com.summit.core.runtime.RuntimeListener;
import com.summit.core.runtime.ShellType;
import com.summit.core.runtime.Workspace;
import com.summit.core.tool.ChoiceDecideGate;
import com.summit.core.tool.DecideRegistry;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecuteCommand;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecutionContext;
import com.summit.core.tool.ToolExecution;
import com.summit.core.tool.ToolInterceptor;
import com.summit.core.tool.ToolRegistry;
import com.summit.runtime.configs.CommonToolConfig;
import com.summit.runtime.coreTools.explicit.ExplicitMeanToolExecutor;
import com.summit.runtime.lifeStyle.DefaultInterceptorProcessor;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end check of the {@code require_choice} (explicit tool) suspension loop, without any
 * model in the loop: a tool call must suspend, publish the user-choice event, be woken up by an
 * external decision (the HTTP endpoint writes through the same registry), and finally return the
 * selected option to the model as the tool output.
 */
class RequireChoiceClosedLoopTest {

    @Test
    void requireChoiceSuspendsPublishesAndResumesWithTheSelectedOption() throws Exception {
        // --- wiring identical to CommonToolAutoConfiguration ---
        ToolRegistry registry = new ToolRegistry();
        List<ExplicitUserMeanEvent> published = new CopyOnWriteArrayList<>();
        RuntimeListener captor = new RuntimeListener() {
            @Override
            public void onExplicitUserMean(ExplicitUserMeanEvent event) {
                published.add(event);
            }
        };
        RuntimeEventPublisher publisher = new RuntimeEventPublisher(List.of(captor));
        DecideRegistry<ChoiceDecideGate, String> choices = new DefaultChoiceDecideRegistry();

        ToolDefinition<ExplicitMeanToolExecutor> definition = ToolDefinition.<ExplicitMeanToolExecutor>builder()
                .executor(new ExplicitMeanToolExecutor(new ObjectMapper(), publisher, choices))
                .id("require_choice")
                .name("require_choice")
                .readOnly(true)
                .parametersJsonSchema("{}")
                .maxOutput(500)
                .timeout(30L)
                .build();
        registry.register("require_choice", definition);

        DefaultInterceptorProcessor<ToolInterceptor, ToolExecution> interceptors =
                new DefaultInterceptorProcessor<>(new ArrayList<>());
        DefaultToolExecutionManager manager = new DefaultToolExecutionManager(
                ToolExecutionContext.builder().toolRegistry(registry).runtimeEventPublisher(publisher).build(),
                interceptors,
                CommonToolConfig.builder().maxToolOutputDisplay(500).build(),
                new DefaultCommandConfirmRegistry(),
                choices);

        ToolCallRequest call = ToolCallRequest.builder()
                .id("tool-exec-1")
                .name("require_choice")
                .arguments("{\"question\":\"这个模块用哪种构建方式？\",\"choice\":[\"Maven\",\"Gradle\"]}")
                .build();
        ToolExecuteCommand command = new ToolExecuteCommand(List.of(call), "exec-1", "session-1", new StubWorkspace());

        Future<List<ToolExecuteResult>> future = Executors.newSingleThreadExecutor().submit(() -> manager.execute(command));

        // 1) the agent asked the user: the call is suspended and a gate is registered
        awaitUntil(() -> choices.size() == 1, 5_000);
        awaitUntil(() -> !published.isEmpty(), 5_000);

        ExplicitUserMeanEvent event = published.get(0);
        assertEquals("tool-exec-1", event.getToolExecutionId());
        assertEquals("这个模块用哪种构建方式？", event.getQuestion());
        assertTrue(event.getChoices().contains("Gradle"), "the options offered by the model must be forwarded");
        assertFalse(future.isDone(), "the tool call must stay suspended until the user decides");

        // 2) the host (HTTP /agent/choices/{id}/decide) writes the choice and wakes the loop
        assertTrue(choices.decide("tool-exec-1", "Gradle"), "the pending gate must accept the decision");

        List<ToolExecuteResult> results = future.get(5, TimeUnit.SECONDS);
        assertEquals(1, results.size());
        ToolExecuteResult result = results.get(0);
        assertTrue(result.isSuccess(), "after the user chose, the tool must succeed");
        assertTrue(result.getToolOutput().contains("Gradle"),
                "the selected option must be returned to the model: " + result.getToolOutput());

        // 3) the gate must be released once the wait is over (no leak)
        awaitUntil(() -> choices.size() == 0, 5_000);
    }

    private static void awaitUntil(BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("condition was not met within " + timeoutMillis + "ms");
            }
            Thread.sleep(20);
        }
    }

    /** Minimal workspace: the require_choice executor never touches the file system. */
    private static final class StubWorkspace implements Workspace {
        @Override
        public String id() {
            return "test-workspace";
        }

        @Override
        public RuntimeEnvironment runtimeEnvironment() {
            return RuntimeEnvironment.builder()
                    .osType(OsType.WINDOWS)
                    .shellType(ShellType.PWSH)
                    .charset(StandardCharsets.UTF_8)
                    .envs(System.getenv())
                    .build();
        }

        @Override
        public String workDir() {
            return Path.of(".").toAbsolutePath().normalize().toString();
        }

        @Override
        public Path resolve(String path) {
            return Path.of(workDir()).resolve(path).normalize();
        }
    }
}
