package com.summit.harness.springbootautoconfigure.config.execution;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.agent.ExecutionState;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.json.ExecutionJson;
import com.summit.core.runtime.loop.ExecutionControlSignal;
import com.summit.core.runtime.loop.InterceptorResult;
import com.summit.core.runtime.loop.LoopContext;
import com.summit.core.runtime.loop.LoopInterceptor;
import com.summit.core.runtime.loop.LoopInterceptorProcessor;
import com.summit.harness.springbootautoconfigure.config.InterceptorConfig;
import com.summit.runtime.loop.control.InMemoryActiveExecutionRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ExecutionRuntimeConfigTest {
    private final ExecutionRuntimeConfig configuration = new ExecutionRuntimeConfig();

    @Test
    void fallbackIsAnInMemoryRegistry() {
        assertInstanceOf(InMemoryActiveExecutionRegistry.class,
                configuration.inMemoryExecutionRepository(ExecutionJson.newObjectMapper()));
    }

    @Test
    void defaultInterceptorProcessorDispatchesEveryRegisteredBeanInOrder() {
        List<String> seen = new ArrayList<>();
        LoopInterceptorProcessor processor = configuration.defaultLoopInterceptorProcessor(
                List.of(interceptor("late", 100, seen), interceptor("early", -1, seen)));

        processor.onLoopStart(context());

        assertEquals(List.of("early", "late"), seen);
    }

    @Test
    void processorAcceptsApplicationInterceptorsWithoutABoundaryOwner() {
        List<String> seen = new ArrayList<>();
        LoopInterceptorProcessor processor = configuration.defaultLoopInterceptorProcessor(
                List.of(interceptor("business", -1, seen)));

        assertEquals(InterceptorResult.NONE, processor.onLoopStart(context()));
        assertEquals(List.of("business"), seen);
    }

    @Test
    void emptyInterceptorRegistryIsValid() {
        LoopInterceptorProcessor processor = configuration.defaultLoopInterceptorProcessor(List.of());

        assertEquals(InterceptorResult.NONE, processor.onLoopStart(context()));
    }

    @Test
    void defaultSignalInterceptorDoesNotConsumeTheModelBudget() {
        LoopContext context = context();
        LoopInterceptor interceptor = new InterceptorConfig().loopInterceptor();

        assertEquals(InterceptorResult.NONE, interceptor.onBeforeModelInvoke(context));
        assertEquals(0, context.execution().getModelAttempts());
    }

    private static LoopContext context() {
        Execution execution = Execution.builder().id("e-1").agentId("a-1")
                .executionState(ExecutionState.CREATED)
                .agentRequest(AgentRequest.builder()
                        .messages(List.of(UserMessageEntity.from("task"))).build())
                .build();
        return new LoopContext(execution, new ExecutionControlSignal("e-1"), 0,
                Map.of(), messages -> { });
    }

    private static LoopInterceptor interceptor(String name, int order, List<String> seen) {
        return new LoopInterceptor() {
            @Override
            public int order() {
                return order;
            }

            @Override
            public InterceptorResult onLoopStart(LoopContext context) {
                seen.add(name);
                return InterceptorResult.NONE;
            }
        };
    }
}
