package com.summit.runtime.loop;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.ContextSpec;
import com.summit.core.agent.Execution;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.runtime.loop.AgenticLoopListener;
import com.summit.core.runtime.loop.InterceptorResult;
import com.summit.core.runtime.loop.InterceptorType;
import com.summit.core.runtime.loop.LoopContext;
import com.summit.core.runtime.loop.LoopInterceptor;
import com.summit.core.runtime.loop.LoopInterceptorProcessor;
import com.summit.core.runtime.loop.LoopMessages;
import com.summit.core.runtime.loop.LoopResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LoopInterceptorFactoryTest {
    @ParameterizedTest
    @EnumSource(InterceptorType.class)
    void processorSeesLaterRegistrationAndRemovalOnEveryHook(InterceptorType selected) throws Exception {
        LoopInterceptorFactory factory = new LoopInterceptorFactory(Map.of());
        LoopInterceptorProcessor processor = new DefaultLoopInterceptorProcessor(factory);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<LoopContext> received = new AtomicReference<>();
        InterceptorResult decision = InterceptorResult.of(LoopResult.continueLoop(), false);
        LoopInterceptor interceptor = factory.register(annotation(selected), context -> {
            calls.incrementAndGet();
            received.set(context);
            return decision;
        });
        Execution execution = Execution.builder().id("factory-execution")
                .agentRequest(AgentRequest.builder()
                        .contextSpec(ContextSpec.builder()
                                .context(List.of(UserMessageEntity.from("test")))
                                .build())
                        .build()).build();
        LoopContext context = LoopContext.builder().loopMessages(LoopMessages.builder().execution(execution).build())
                .consecutiveCompactTurns(() -> 2).build();

        for (InterceptorType hook : InterceptorType.values()) {
            assertSame(hook == selected ? decision : InterceptorResult.NONE,
                    invoke(processor, hook, context, execution), hook.name());
        }

        assertEquals(1, calls.get());
        assertEquals(-300, interceptor.order());
        assertFalse(interceptor.catchErr());
        assertSame(interceptor, factory.get().get(selected.name()));
        if (selected == InterceptorType.RUN_END) {
            assertSame(execution, received.get().getLoopMessages().getExecution());
        } else {
            assertSame(context, received.get());
        }
        assertSame(interceptor, factory.unRegister(selected.name(), null));
        assertSame(InterceptorResult.NONE, invoke(processor, selected, context, execution));
        assertEquals(1, calls.get(), "Unregistered listeners must not be called by an existing processor");
    }

    @Test
    void importsExistingBeansAndReturnsSortedIndependentSnapshots() {
        LoopInterceptor early = () -> -500;
        LoopInterceptor late = () -> 30;
        LoopInterceptorFactory factory = new LoopInterceptorFactory(Map.of("early", early, "late", late));
        Map<String, LoopInterceptor> snapshot = factory.get();
        List<LoopInterceptor> list = factory.getInterceptors();

        assertEquals(List.of("early", "late"), new ArrayList<>(snapshot.keySet()));
        assertEquals(List.of(early, late), list);
        snapshot.clear();
        factory.register("middle", () -> -10);
        assertEquals(3, factory.get().size());
        assertEquals(List.of(early, late), list);
        assertEquals(List.of(-500, -10, 30), factory.getInterceptors().stream().map(LoopInterceptor::order).toList());
    }

    @Test
    void rejectsDuplicateManualNamesWithoutReplacingTheOriginal() {
        LoopInterceptor original = () -> 1;
        LoopInterceptorFactory factory = new LoopInterceptorFactory(Map.of("existing", original));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> factory.register("existing", () -> 2));

        assertTrue(failure.getMessage().contains("existing"));
        assertSame(original, factory.get().get("existing"));
    }

    @Test
    void rejectsDuplicateAnnotationNamesWithoutReplacingTheOriginal() throws Exception {
        LoopInterceptorFactory factory = new LoopInterceptorFactory(Map.of());
        AgenticLoopListener listener = annotation(InterceptorType.LOOP_START);
        LoopInterceptor original = factory.register(listener, context -> InterceptorResult.NONE);

        assertThrows(IllegalStateException.class,
                () -> factory.register(listener, context -> InterceptorResult.NONE));
        assertSame(original, factory.get().get("LOOP_START"));
    }

    @Test
    void rejectsNullCallbackBeforeRegisteringAnything() throws Exception {
        LoopInterceptorFactory factory = new LoopInterceptorFactory(Map.of());
        AgenticLoopListener listener = annotation(InterceptorType.LOOP_START);

        assertThrows(NullPointerException.class, () -> factory.register(listener, null));
        assertTrue(factory.get().isEmpty());
    }

    @Test
    void generatesDistinctNamesForUnnamedRegistrations() {
        LoopInterceptorFactory factory = new LoopInterceptorFactory(Map.of());
        LoopInterceptor first = () -> 4;
        LoopInterceptor second = () -> 4;

        factory.register(first);
        factory.register("", second);

        assertEquals(2, factory.get().size());
        assertTrue(factory.get().containsValue(first));
        assertTrue(factory.get().containsValue(second));
    }

    @Test
    void unregistersByNameAndByOrderAndStopsFutureDispatch() {
        AtomicInteger calls = new AtomicInteger();
        LoopInterceptor first = new LoopInterceptor() {
            public int order() { return -3; }
            public InterceptorResult onLoopStart(LoopContext context) {
                calls.incrementAndGet();
                return InterceptorResult.NONE;
            }
        };
        LoopInterceptor second = () -> 8;
        LoopInterceptorFactory factory = new LoopInterceptorFactory(Map.of("first", first, "second", second));

        assertSame(first, factory.unRegister("first", null));
        new DefaultLoopInterceptorProcessor(factory).onLoopStart(null);
        assertEquals(0, calls.get());
        assertFalse(factory.get().containsKey("first"));
        assertSame(second, factory.unRegister(null, 8));
        assertTrue(factory.get().isEmpty());
        assertNull(factory.unRegister("missing", null));
    }

    @Test
    void ambiguousOrderDoesNotRemoveAnyEntry() {
        LoopInterceptorFactory factory = new LoopInterceptorFactory(Map.of("first", () -> 4, "second", () -> 4));

        assertThrows(RuntimeException.class, () -> factory.unRegister(null, 4));
        assertEquals(2, factory.get().size());
    }

    @Test
    void nameAndOrderMatchingDifferentEntriesIsAmbiguous() {
        LoopInterceptorFactory factory = new LoopInterceptorFactory(Map.of("first", () -> 4, "second", () -> 8));

        assertThrows(RuntimeException.class, () -> factory.unRegister("first", 8));
        assertEquals(2, factory.get().size());
    }

    @Test
    void annotationControlsWhetherTheProcessorContainsCallbackFailures() throws Exception {
        LoopInterceptorFactory factory = new LoopInterceptorFactory(Map.of());
        IllegalStateException failure = new IllegalStateException("callback failed");
        factory.register(annotation(InterceptorType.LOOP_START), context -> { throw failure; });
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> new DefaultLoopInterceptorProcessor(factory).onLoopStart(null)));

        AgenticLoopListener contained = Hooks.class.getDeclaredMethod("contained", LoopContext.class)
                .getAnnotation(AgenticLoopListener.class);
        LoopInterceptorFactory tolerant = new LoopInterceptorFactory(Map.of());
        tolerant.register(contained, context -> { throw failure; });
        AtomicInteger laterCalls = new AtomicInteger();
        tolerant.register("later", new LoopInterceptor() {
            public int order() { return 0; }
            public InterceptorResult onLoopStart(LoopContext context) {
                laterCalls.incrementAndGet();
                return InterceptorResult.NONE;
            }
        });
        assertSame(InterceptorResult.NONE,
                new DefaultLoopInterceptorProcessor(tolerant).onLoopStart(null));
        assertEquals(1, laterCalls.get());
    }

    private AgenticLoopListener annotation(InterceptorType type) throws Exception {
        return Hooks.class.getDeclaredMethod(type.name(), LoopContext.class).getAnnotation(AgenticLoopListener.class);
    }

    private InterceptorResult invoke(LoopInterceptorProcessor interceptor, InterceptorType hook,
                                     LoopContext context, Execution execution) {
        return switch (hook) {
            case BEFORE_INVOKE -> interceptor.onBeforeModelInvoke(context);
            case AFTER_INVOKE -> interceptor.onAfterModelInvoke(context, null);
            case LOOP_START -> interceptor.onLoopStart(context);
            case LOOP_END -> interceptor.onLoopEnd(context);
            case AFTER_TOOL_CALL -> interceptor.onAfterToolCall(context, List.of());
            case BEFORE_TOOL_CALL -> interceptor.onBeforeToolCall(context);
            case BEFORE_COMPLETE -> interceptor.onBeforeComplete(context);
            case RUN_END -> interceptor.onRunEnd(execution);
        };
    }

    private static class Hooks {
        @AgenticLoopListener(value = InterceptorType.BEFORE_INVOKE, name = "BEFORE_INVOKE", order = -300)
        InterceptorResult BEFORE_INVOKE(LoopContext context) { return InterceptorResult.NONE; }
        @AgenticLoopListener(value = InterceptorType.AFTER_INVOKE, name = "AFTER_INVOKE", order = -300)
        InterceptorResult AFTER_INVOKE(LoopContext context) { return InterceptorResult.NONE; }
        @AgenticLoopListener(value = InterceptorType.LOOP_START, name = "LOOP_START", order = -300)
        InterceptorResult LOOP_START(LoopContext context) { return InterceptorResult.NONE; }
        @AgenticLoopListener(value = InterceptorType.LOOP_END, name = "LOOP_END", order = -300)
        InterceptorResult LOOP_END(LoopContext context) { return InterceptorResult.NONE; }
        @AgenticLoopListener(value = InterceptorType.AFTER_TOOL_CALL, name = "AFTER_TOOL_CALL", order = -300)
        InterceptorResult AFTER_TOOL_CALL(LoopContext context) { return InterceptorResult.NONE; }
        @AgenticLoopListener(value = InterceptorType.BEFORE_TOOL_CALL, name = "BEFORE_TOOL_CALL", order = -300)
        InterceptorResult BEFORE_TOOL_CALL(LoopContext context) { return InterceptorResult.NONE; }
        @AgenticLoopListener(value = InterceptorType.BEFORE_COMPLETE, name = "BEFORE_COMPLETE", order = -300)
        InterceptorResult BEFORE_COMPLETE(LoopContext context) { return InterceptorResult.NONE; }
        @AgenticLoopListener(value = InterceptorType.RUN_END, name = "RUN_END", order = -300)
        InterceptorResult RUN_END(LoopContext context) { return InterceptorResult.NONE; }
        @AgenticLoopListener(value = InterceptorType.LOOP_START, name = "contained", order = -300, catchErr = true)
        InterceptorResult contained(LoopContext context) { return InterceptorResult.NONE; }
    }
}
