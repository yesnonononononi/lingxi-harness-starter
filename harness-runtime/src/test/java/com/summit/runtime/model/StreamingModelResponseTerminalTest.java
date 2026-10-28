package com.summit.runtime.model;

import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.event.AgentCompleteTextEvent;
import com.summit.core.conversation.event.AgentPartialTextEvent;
import com.summit.core.conversation.event.AgentPartialThinkingEvent;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.model.streaming.StreamingHandler;
import com.summit.core.runtime.RuntimeListener;
import com.summit.core.runtime.loop.ExecutionControlSignal;
import com.summit.core.runtime.loop.suspension.ExecutionInterruptedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingModelResponseTerminalTest {
    @ParameterizedTest
    @ValueSource(strings = {"text", "thinking", "tool"})
    void errorClosesTheKnownHandleBeforeWakingTheInvoker(String kind) {
        Fixture fixture = new Fixture(null);
        Transport transport = new Transport();
        delta(fixture.handler, kind, transport);
        IOException failure = new IOException("upstream failed");
        CompletableFuture<Void> observed = fixture.future.handle((result, error) -> {
            assertTrue(transport.isCancelled());
            assertSame(failure, error);
            return null;
        });

        fixture.handler.onError(failure);

        observed.join();
        assertEquals(1, transport.cancellations.get());
        assertSame(failure, assertThrows(CompletionException.class, fixture.future::join).getCause());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void errorBeforeAnyDeltaRejectsAndCancelsLateCallbacks(boolean controlled) {
        Fixture fixture = new Fixture(controlled ? new ExecutionControlSignal("execution") : null);
        IOException failure = new IOException("failed before first delta");
        fixture.handler.onError(failure);

        for (String kind : List.of("text", "thinking", "tool")) {
            Transport late = new Transport();
            delta(fixture.handler, kind, late);
            assertEquals(1, late.cancellations.get());
        }
        fixture.handler.onFinalResponse(null);
        fixture.handler.onError(new IOException("duplicate"));

        assertTrue(fixture.events.isEmpty());
        assertSame(failure, assertThrows(CompletionException.class, fixture.future::join).getCause());
    }

    @Test
    void successfulAttemptPublishesOnceAndRejectsLaterCallbacks() {
        Fixture fixture = new Fixture(null);
        ChatResponseEntity answer = response("done");

        fixture.handler.onFinalResponse(answer);
        fixture.handler.onFinalResponse(response("duplicate"));
        for (String kind : List.of("text", "thinking", "tool")) {
            Transport late = new Transport();
            delta(fixture.handler, kind, late);
            assertEquals(1, late.cancellations.get());
        }
        fixture.handler.onError(new IOException("late error"));

        assertSame(answer, fixture.future.join());
        assertEquals("response", answer.getResponseId());
        assertEquals(List.of("complete"), fixture.events);
    }

    @Test
    void successClaimsTheTerminalBeforePublishingAndWakesTheInvokerAfterPublishing() throws Exception {
        CountDownLatch publishing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean futureDoneDuringEvent = new AtomicBoolean(true);
        Fixture fixture = new Fixture(null);
        RuntimeListener listener = new RuntimeListener() {
            @Override
            public void onCompleteText(AgentCompleteTextEvent event) {
                futureDoneDuringEvent.set(fixture.future.isDone());
                publishing.countDown();
                await(release);
            }
        };
        StreamingModelResponseBehaveDecider handler = fixture.createHandler(null,
                new RuntimeEventPublisher(List.of(listener)));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> finishing = worker.submit(() -> handler.onFinalResponse(response("done")));
            assertTrue(publishing.await(5, TimeUnit.SECONDS));

            handler.onError(new IOException("losing error"));
            assertFalse(fixture.future.isDone());
            release.countDown();
            finishing.get(5, TimeUnit.SECONDS);

            assertFalse(futureDoneDuringEvent.get());
            assertEquals("done", fixture.future.join().getAiMessageEntity().getText());
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    void failureClaimsTheTerminalBeforeTransportCleanupAndRejectsConcurrentCompletion() throws Exception {
        Fixture fixture = new Fixture(null);
        CountDownLatch closing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Transport transport = new Transport(() -> {
            closing.countDown();
            await(release);
        });
        fixture.handler.onPartialResponse("before", transport);
        IOException failure = new IOException("winning error");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> failing = worker.submit(() -> fixture.handler.onError(failure));
            assertTrue(closing.await(5, TimeUnit.SECONDS));

            fixture.handler.onFinalResponse(response("losing completion"));
            assertFalse(fixture.future.isDone());
            release.countDown();
            failing.get(5, TimeUnit.SECONDS);

            assertEquals(List.of("text"), fixture.events);
            assertSame(failure, assertThrows(CompletionException.class, fixture.future::join).getCause());
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void finalCallbackObservesControlAndClosesPreviouslyObtainedHandle(boolean cancel) {
        ExecutionControlSignal control = new ExecutionControlSignal("execution");
        Fixture fixture = new Fixture(control);
        Transport transport = new Transport();
        fixture.handler.onPartialThinking("before", transport);
        if (cancel) control.requireCancel();
        else control.requireSuspend();

        fixture.handler.onFinalResponse(response("dropped"));
        fixture.handler.onFinalResponse(response("duplicate"));

        ExecutionInterruptedException failure = assertInstanceOf(ExecutionInterruptedException.class,
                assertThrows(CompletionException.class, fixture.future::join).getCause());
        assertEquals(cancel ? ExecutionInterruptedException.Kind.CANCEL : ExecutionInterruptedException.Kind.SUSPEND,
                failure.kind());
        assertEquals(1, transport.cancellations.get());
        assertEquals(List.of("thinking"), fixture.events);
    }

    @Test
    void cancellationFailureDoesNotReplaceTheOriginalFailureOrLeaveTheFuturePending() {
        Fixture fixture = new Fixture(null);
        Transport transport = new Transport(() -> { throw new IllegalStateException("close failed"); });
        fixture.handler.onPartialResponse("before", transport);
        IOException failure = new IOException("original");

        fixture.handler.onError(failure);
        fixture.handler.onFinalResponse(response("dropped"));

        assertEquals(1, transport.cancellations.get());
        assertSame(failure, assertThrows(CompletionException.class, fixture.future::join).getCause());
        assertEquals(List.of("text"), fixture.events);
    }

    @Test
    void invalidFinalResponseSettlesTheClaimedAttemptAndClosesItsTransport() {
        Fixture fixture = new Fixture(null);
        Transport transport = new Transport();
        fixture.handler.onPartialResponse("before", transport);

        NullPointerException failure = assertThrows(NullPointerException.class,
                () -> fixture.handler.onFinalResponse(ChatResponseEntity.builder().build()));

        assertTrue(fixture.future.isCompletedExceptionally());
        assertSame(failure, assertThrows(CompletionException.class, fixture.future::join).getCause());
        assertEquals(1, transport.cancellations.get());
        assertEquals(List.of("text"), fixture.events);
    }

    private static void delta(StreamingModelResponseBehaveDecider handler, String kind, Transport transport) {
        switch (kind) {
            case "text" -> handler.onPartialResponse("delta", transport);
            case "thinking" -> handler.onPartialThinking("delta", transport);
            case "tool" -> handler.onPartialToolCall("{}", transport);
            default -> throw new IllegalArgumentException(kind);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    private static ChatResponseEntity response(String text) {
        return ChatResponseEntity.builder().aiMessageEntity(AiMessageEntity.builder().text(text).build()).build();
    }

    private static final class Transport implements StreamingHandler {
        private final AtomicInteger cancellations = new AtomicInteger();
        private final Runnable afterCancel;

        private Transport() { this(() -> { }); }
        private Transport(Runnable afterCancel) { this.afterCancel = afterCancel; }
        public void cancel() { cancellations.incrementAndGet(); afterCancel.run(); }
        public boolean isCancelled() { return cancellations.get() > 0; }
    }

    private static final class Fixture {
        private final CompletableFuture<ChatResponseEntity> future = new CompletableFuture<>();
        private final List<String> events = new CopyOnWriteArrayList<>();
        private final StreamingModelResponseBehaveDecider handler;

        private Fixture(ExecutionControlSignal control) {
            RuntimeListener listener = new RuntimeListener() {
                public void onPartialText(AgentPartialTextEvent event) { events.add("text"); }
                public void onPartialThinking(AgentPartialThinkingEvent event) { events.add("thinking"); }
                public void onCompleteText(AgentCompleteTextEvent event) { events.add("complete"); }
            };
            handler = createHandler(control, new RuntimeEventPublisher(List.of(listener)));
        }

        private StreamingModelResponseBehaveDecider createHandler(ExecutionControlSignal control,
                                                                 RuntimeEventPublisher publisher) {
            return new StreamingModelResponseBehaveDecider(publisher,
                    StreamingModelResponseBehaveDecider.StreamingResponseContext.builder().executionId("execution")
                            .agentId("agent").responseId("response").future(future).build(), control);
        }
    }
}
