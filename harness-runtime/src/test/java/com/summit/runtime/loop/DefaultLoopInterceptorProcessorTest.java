package com.summit.runtime.loop;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.agent.ExecutionState;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.runtime.loop.*;
import com.summit.core.tool.ToolExecuteResult;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The processor owns the dispatch contract of the interceptor registry:
 *
 * <ul>
 *   <li>every registered interceptor runs on every hook, in ascending {@code order()};</li>
 *   <li>a callback that throws is logged and contained — it neither propagates to the loop nor
 *       prevents the remaining interceptors from running;</li>
 *   <li>each hook hands the original payload through untouched (context / response / results /
 *       execution), so an interceptor can never be handed a copy that the run does not use.</li>
 * </ul>
 */
class DefaultLoopInterceptorProcessorTest {

    private static final List<String> ALL_HOOKS = List.of(
            "loopStart", "beforeModel", "afterModel", "beforeTool", "afterTool", "beforeComplete", "loopEnd", "runEnd");

    @Test
    void interceptorsRunInAscendingOrderRegardlessOfRegistrationOrder() {
        List<String> seen = new ArrayList<>();

        processor(loopStartRecorder("late", 50, seen),
                loopStartRecorder("early", -10, seen),
                loopStartRecorder("middle", 0, seen)).onLoopStart(context());

        assertEquals(List.of("early", "middle", "late"), seen);
    }

    /** {@code order()} has no uniqueness constraint: equal orders keep the registration order. */
    @Test
    void equalOrdersKeepRegistrationOrder() {
        List<String> seen = new ArrayList<>();

        processor(loopStartRecorder("first", 7, seen),
                loopStartRecorder("second", 7, seen)).onLoopStart(context());

        assertEquals(List.of("first", "second"), seen);
    }

    @Test
    void aFailingInterceptorNeitherPropagatesNorStopsTheRest() {
        AtomicInteger survived = new AtomicInteger();
        LoopInterceptor boom = new LoopInterceptor() {
            @Override
            public int order() {
                return 0;
            }

            @Override
            public InterceptorResult onLoopStart(LoopContext context) {
                throw new IllegalStateException("boom");
            }

            @Override
            public InterceptorResult onBeforeModelInvoke(LoopContext context) {
                throw new IllegalStateException("boom");
            }

            @Override
            public InterceptorResult onAfterModelInvoke(LoopContext context, ChatResponseEntity response) {
                throw new IllegalStateException("boom");
            }

            @Override
            public InterceptorResult onBeforeToolCall(LoopContext context) {
                throw new IllegalStateException("boom");
            }

            @Override
            public InterceptorResult onAfterToolCall(LoopContext context, List<ToolExecuteResult> results) {
                throw new IllegalStateException("boom");
            }

            @Override
            public InterceptorResult onLoopEnd(LoopContext context) {
                throw new IllegalStateException("boom");
            }

            @Override
            public InterceptorResult onBeforeComplete(LoopContext context) {
                throw new IllegalStateException("boom");
            }

            @Override
            public InterceptorResult onRunEnd(Execution execution) {
                throw new IllegalStateException("boom");
            }
        };
        DefaultLoopInterceptorProcessor processor =
                processor(boom, counter(10, survived));

        LoopContext context = context();
        List<ToolExecuteResult> results = List.of(ToolExecuteResult.success("ok"));
        Execution execution = execution();

        assertDoesNotThrow(() -> processor.onLoopStart(context));
        assertDoesNotThrow(() -> processor.onBeforeModelInvoke(context));
        assertDoesNotThrow(() -> processor.onAfterModelInvoke(context, response()));
        assertDoesNotThrow(() -> processor.onBeforeToolCall(context));
        assertDoesNotThrow(() -> processor.onAfterToolCall(context, results));
        assertDoesNotThrow(() -> processor.onBeforeComplete(context));
        assertDoesNotThrow(() -> processor.onLoopEnd(context));
        assertDoesNotThrow(() -> processor.onRunEnd(execution));

        assertEquals(ALL_HOOKS.size(), survived.get(),
                "every hook must still reach the interceptor registered after the failing one");
    }

    @Test
    void everyHookReachesEveryInterceptorWithItsPayload() {
        AtomicReference<LoopContext> contextSeen = new AtomicReference<>();
        AtomicReference<ChatResponseEntity> responseSeen = new AtomicReference<>();
        AtomicReference<List<ToolExecuteResult>> resultsSeen = new AtomicReference<>();
        AtomicReference<Execution> executionSeen = new AtomicReference<>();
        List<String> seen = new ArrayList<>();
        LoopInterceptor spy = new LoopInterceptor() {
            @Override
            public int order() {
                return 0;
            }

            @Override
            public InterceptorResult onLoopStart(LoopContext context) {
                seen.add("loopStart");
                contextSeen.set(context);
                return InterceptorResult.NONE;
            }

            @Override
            public InterceptorResult onBeforeModelInvoke(LoopContext context) {
                seen.add("beforeModel");
                contextSeen.set(context);
                return InterceptorResult.NONE;
            }

            @Override
            public InterceptorResult onAfterModelInvoke(LoopContext context, ChatResponseEntity response) {
                seen.add("afterModel");
                responseSeen.set(response);
                return InterceptorResult.NONE;
            }

            @Override
            public InterceptorResult onBeforeToolCall(LoopContext context) {
                seen.add("beforeTool");
                contextSeen.set(context);
                return InterceptorResult.NONE;
            }

            @Override
            public InterceptorResult onAfterToolCall(LoopContext context, List<ToolExecuteResult> results) {
                seen.add("afterTool");
                resultsSeen.set(results);
                return InterceptorResult.NONE;
            }

            @Override
            public InterceptorResult onLoopEnd(LoopContext context) {
                seen.add("loopEnd");
                contextSeen.set(context);
                return InterceptorResult.NONE;
            }

            @Override
            public InterceptorResult onBeforeComplete(LoopContext context) {
                seen.add("beforeComplete");
                contextSeen.set(context);
                return InterceptorResult.NONE;
            }

            @Override
            public InterceptorResult onRunEnd(Execution execution) {
                seen.add("runEnd");
                executionSeen.set(execution);
                return InterceptorResult.NONE;
            }
        };
        DefaultLoopInterceptorProcessor processor = processor(spy);

        LoopContext context = context();
        ChatResponseEntity response = response();
        List<ToolExecuteResult> results = List.of(ToolExecuteResult.success("ok"));
        Execution execution = execution();

        processor.onLoopStart(context);
        processor.onBeforeModelInvoke(context);
        processor.onAfterModelInvoke(context, response);
        processor.onBeforeToolCall(context);
        processor.onAfterToolCall(context, results);
        processor.onBeforeComplete(context);
        processor.onLoopEnd(context);
        processor.onRunEnd(execution);

        assertEquals(ALL_HOOKS, seen);
        assertSame(context, contextSeen.get());
        assertSame(response, responseSeen.get());
        assertSame(results, resultsSeen.get());
        assertSame(execution, executionSeen.get());
    }

    @Test
    void anEmptyRegistryIsSafeOnEveryHook() {
        DefaultLoopInterceptorProcessor processor = processor();

        assertDoesNotThrow(() -> processor.onLoopStart(context()));
        assertDoesNotThrow(() -> processor.onBeforeModelInvoke(context()));
        assertDoesNotThrow(() -> processor.onAfterModelInvoke(context(), response()));
        assertDoesNotThrow(() -> processor.onBeforeToolCall(context()));
        assertDoesNotThrow(() -> processor.onAfterToolCall(context(), List.of()));
        assertDoesNotThrow(() -> processor.onBeforeComplete(context()));
        assertDoesNotThrow(() -> processor.onLoopEnd(context()));
        assertDoesNotThrow(() -> processor.onRunEnd(execution()));
    }

    @Test
    void completionDecisionRunsInOrderAndStopsLaterInterceptors() {
        List<String> seen = new ArrayList<>();
        InterceptorResult suspended = InterceptorResult.of(LoopResult.suspended("waiting for child"), false);
        LoopInterceptor late = new LoopInterceptor() {
            public int order() { return 10; }
            public InterceptorResult onBeforeComplete(LoopContext context) {
                seen.add("late");
                return InterceptorResult.NONE;
            }
        };
        LoopInterceptor early = new LoopInterceptor() {
            public int order() { return -1; }
            public InterceptorResult onBeforeComplete(LoopContext context) {
                seen.add("early");
                return suspended;
            }
        };
        assertSame(suspended, processor(late, early).onBeforeComplete(context()));
        assertEquals(List.of("early"), seen);
    }

    @Test
    void mandatoryCompletionCallbackFailurePropagates() {
        IllegalStateException failure = new IllegalStateException("completion decision failed");
        LoopInterceptor mandatory = new LoopInterceptor() {
            public int order() { return 0; }
            public boolean catchErr() { return false; }
            public InterceptorResult onBeforeComplete(LoopContext context) { throw failure; }
        };
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> processor(mandatory).onBeforeComplete(context())));
    }

    @Test
    void explicitContinuationStopsTheCompletionInterceptorChain() {
        AtomicInteger laterCalls = new AtomicInteger();
        InterceptorResult continuation = InterceptorResult.of(LoopResult.continueLoop(), false);
        LoopInterceptor early = new LoopInterceptor() {
            public int order() { return -600; }
            public InterceptorResult onBeforeComplete(LoopContext context) { return continuation; }
        };

        assertSame(continuation, processor(counter(0, laterCalls), early).onBeforeComplete(context()));
        assertEquals(0, laterCalls.get());
    }

    @Test
    void invalidResultStackIdentifiesTheProducingInterceptorWithoutConstructorOrder() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> processor(new InvalidResultInterceptor(false)).onBeforeComplete(context()));

        assertTrue(Arrays.stream(failure.getStackTrace()).anyMatch(frame ->
                frame.getClassName().equals(InvalidResultInterceptor.class.getName())
                        && frame.getMethodName().equals("onBeforeComplete") && frame.getLineNumber() > 0));
    }

    @Test
    void containedInvalidResultLogsItsOriginalStackAndStillDelegates() {
        Logger logger = (Logger) LoggerFactory.getLogger(DefaultLoopInterceptorProcessor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        AtomicInteger laterCalls = new AtomicInteger();
        try {
            assertSame(InterceptorResult.NONE,
                    processor(new InvalidResultInterceptor(true), counter(0, laterCalls))
                            .onBeforeComplete(context()));

            assertEquals(1, laterCalls.get());
            assertEquals(1, appender.list.size());
            ILoggingEvent event = appender.list.getFirst();
            assertTrue(event.getFormattedMessage().contains(InvalidResultInterceptor.class.getName()));
            assertTrue(event.getFormattedMessage().contains("order=-600"));
            Throwable original = assertInstanceOf(ThrowableProxy.class, event.getThrowableProxy()).getThrowable();
            assertInstanceOf(IllegalArgumentException.class, original);
            assertTrue(Arrays.stream(original.getStackTrace()).anyMatch(frame ->
                    frame.getClassName().equals(InvalidResultInterceptor.class.getName())
                            && frame.getMethodName().equals("onBeforeComplete") && frame.getLineNumber() > 0));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static final class InvalidResultInterceptor implements LoopInterceptor {
        private final boolean containFailure;

        private InvalidResultInterceptor(boolean containFailure) {
            this.containFailure = containFailure;
        }

        public int order() { return -600; }
        public boolean catchErr() { return containFailure; }
        public InterceptorResult onBeforeComplete(LoopContext context) {
            return InterceptorResult.of(LoopResult.suspended("waiting"), true);
        }
    }

    @Test
    void aNullRegistryIsRejectedAtConstruction() {
        assertThrows(NullPointerException.class, () -> new DefaultLoopInterceptorProcessor(null));
    }

    /** {@link LoopInterceptor#NOOP} stays a lambda-compatible default with the lowest order. */
    @Test
    void noopIsSafeAndOrdersFirst() {
        assertEquals(0, LoopInterceptor.NOOP.order());
        assertDoesNotThrow(() -> LoopInterceptor.NOOP.onAfterToolCall(context(), List.of()));
        assertDoesNotThrow(() -> LoopInterceptor.NOOP.onBeforeComplete(context()));
        assertDoesNotThrow(() -> LoopInterceptor.NOOP.onRunEnd(execution()));
    }

    private static DefaultLoopInterceptorProcessor processor(LoopInterceptor... interceptors) {
        return new DefaultLoopInterceptorProcessor(List.of(interceptors));
    }

    /** Records its name on {@code onLoopStart}, which is enough to observe dispatch order. */
    private static LoopInterceptor loopStartRecorder(String name, int order, List<String> seen) {
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

    /** Counts every hook that reaches it: a contained failure cannot inflate the count. */
    private static LoopInterceptor counter(int order, AtomicInteger calls) {
        return new LoopInterceptor() {
            @Override
            public int order() {
                return order;
            }

            @Override
            public InterceptorResult onLoopStart(LoopContext context) {
                calls.incrementAndGet();
                return InterceptorResult.NONE;
            }

            @Override
            public InterceptorResult onBeforeModelInvoke(LoopContext context) {
                calls.incrementAndGet();
                return InterceptorResult.NONE;
            }

            @Override
            public InterceptorResult onAfterModelInvoke(LoopContext context, ChatResponseEntity response) {
                calls.incrementAndGet();
                return InterceptorResult.NONE;
            }

            @Override
            public InterceptorResult onBeforeToolCall(LoopContext context) {
                calls.incrementAndGet();
                return InterceptorResult.NONE;
            }

            @Override
            public InterceptorResult onAfterToolCall(LoopContext context, List<ToolExecuteResult> results) {
                calls.incrementAndGet();
                return InterceptorResult.NONE;
            }

            @Override
            public InterceptorResult onLoopEnd(LoopContext context) {
                calls.incrementAndGet();
                return InterceptorResult.NONE;
            }

            @Override
            public InterceptorResult onBeforeComplete(LoopContext context) {
                calls.incrementAndGet();
                return InterceptorResult.NONE;
            }

            @Override
            public InterceptorResult onRunEnd(Execution execution) {
                calls.incrementAndGet();
                return InterceptorResult.NONE;
            }
        };
    }

    /**
     * The round number is derived from the execution's attempt counter, so it stays live across the
     * whole run: a context built once at round 0 must report later rounds instead of freezing at 0.
     */
    @Test
    void loopCountTracksRoundsAcrossTheWholeRun() {
        Execution execution = execution();
        LoopContext context = new LoopContext(LoopMessages.builder().execution(execution).build(),
                new ExecutionControlSignal("e-1"), 0, messages -> { });

        assertEquals(0, context.loopCount());
        execution.incrementModelAttempts();
        execution.incrementModelAttempts();
        assertEquals(2, context.loopCount(), "a stored copy would have stayed at 0");
    }

    private static LoopContext context() {
        return new LoopContext(LoopMessages.builder().execution(execution()).build(),
                new ExecutionControlSignal("e-1"), 0, messages -> { });
    }

    @Test
    void compactionCounterIsLiveWithoutChangingTheExistingConstructor() {
        AtomicInteger counter = new AtomicInteger();
        LoopMessages messages = LoopMessages.builder().execution(execution()).build();
        ExecutionControlSignal signal = new ExecutionControlSignal("e-1");
        LoopContext live = LoopContext.withCompactionCounter(messages, signal, counter::get, ignored -> { });
        LoopContext fixed = new LoopContext(messages, signal, 7, ignored -> { });
        LoopContext nullable = new LoopContext(messages, signal, null, ignored -> { });

        assertEquals(0, live.getConsecutiveCompactTurns());
        counter.set(2);
        assertEquals(2, live.getConsecutiveCompactTurns());
        assertEquals(7, fixed.getConsecutiveCompactTurns());
        assertNull(nullable.getConsecutiveCompactTurns());
    }

    private static ChatResponseEntity response() {
        return ChatResponseEntity.builder()
                .aiMessageEntity(AiMessageEntity.builder().text("answer").build()).build();
    }

    private static Execution execution() {
        return Execution.builder().id("e-1").agentId("a-1").executionState(ExecutionState.CREATED)
                .agentRequest(AgentRequest.builder()
                        .messages(List.of(UserMessageEntity.from("task"))).build())
                .build();
    }
}
