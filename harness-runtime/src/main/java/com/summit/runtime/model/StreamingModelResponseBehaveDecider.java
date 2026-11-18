package com.summit.runtime.model;

import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.event.*;
import com.summit.core.exception.ResponseTimeoutException;
import com.summit.core.model.ModelInvocationUsage;
import com.summit.core.model.streaming.StreamingHandler;
import com.summit.core.model.streaming.StreamingModelResponseHandler;
import com.summit.core.runtime.loop.ExecutionControlSignal;
import com.summit.core.runtime.loop.suspension.ExecutionInterruptedException;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Getter
public class StreamingModelResponseBehaveDecider implements StreamingModelResponseHandler {
    public static final Duration DEFAULT_ACTIVE_WINDOW = Duration.ofSeconds(30);
    public static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(5);

    private static final ExecutorService CANCEL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final AtomicBoolean reject = new AtomicBoolean(false);
    @Getter(AccessLevel.NONE)
    private final AtomicReference<StreamingHandler> transport = new AtomicReference<>();
    /**
     * Tool call ids already announced. Deduplication must not lose an entry to a concurrent delta:
     * a lost entry drops the announcement silently instead of failing loudly.
     */
    private final Set<String> hasSentPartialToolCall = ConcurrentHashMap.newKeySet();
    private int responseOffset;
    private int thinkingOffset;
    private final ResponseWatchDog watchDog;
    private final long activeWindow;
    private final AtomicLong lastActivityTime = new AtomicLong();


    @Builder
    public record StreamingResponseContext(
            String executionId, String agentId,
            String responseId,
            CompletableFuture<ChatResponseEntity> future,
            Map<String, Object> eventMetaData,
            ModelInvocationUsage invocationUsage
    ) {
        public StreamingResponseContext {
            eventMetaData = eventMetaData == null ? Map.of() : Map.copyOf(eventMetaData);
        }

    }

    private final RuntimeEventPublisher runtimeEventPublisher;
    private final AgentEventPublisher agentEventPublisher;
    private final StreamingResponseContext streamingResponseContext;

    /**
     * Cooperative control signal of the execution this stream belongs to, or {@code null} when the
     * runtime has no signal for it. Read on every delta so a suspend or cancel request issued while
     * the model is still producing output takes effect immediately instead of waiting for the
     * stream to finish.
     */
    private final ExecutionControlSignal control;

    public StreamingModelResponseBehaveDecider(
            RuntimeEventPublisher runtimeEventPublisher,
            AgentEventPublisher agentEventPublisher,
            StreamingResponseContext streamingResponseContext,
            ExecutionControlSignal control) {
        this(runtimeEventPublisher, agentEventPublisher, streamingResponseContext, control,
                DEFAULT_ACTIVE_WINDOW, DEFAULT_HEARTBEAT_INTERVAL);
    }

    /**
     * Creates a streaming decider with configurable idle-timeout window and watchdog heartbeat interval.
     *
     * @param activeWindow      max allowed silence between stream events; defaults to 30 seconds when {@code null}
     * @param heartbeatInterval watchdog polling interval; defaults to 5 seconds when {@code null}
     */
    public StreamingModelResponseBehaveDecider(
            RuntimeEventPublisher runtimeEventPublisher,
            AgentEventPublisher agentEventPublisher,
            StreamingResponseContext streamingResponseContext,
            ExecutionControlSignal control,
            Duration activeWindow,
            Duration heartbeatInterval) {
        this.runtimeEventPublisher = runtimeEventPublisher;
        this.agentEventPublisher = agentEventPublisher;
        this.streamingResponseContext = Objects.requireNonNull( streamingResponseContext, "streamingResponseContext must not be null");
        this.control = control;

        Duration effectiveWindow = activeWindow != null ? activeWindow : DEFAULT_ACTIVE_WINDOW;

        if (effectiveWindow.isZero() || effectiveWindow.isNegative()) {
            throw new IllegalArgumentException("activeWindow must be positive");
        }

        Duration effectiveHeartbeat = heartbeatInterval != null ? heartbeatInterval : DEFAULT_HEARTBEAT_INTERVAL;

        if (effectiveHeartbeat.isZero() || effectiveHeartbeat.isNegative()) {
            throw new IllegalArgumentException("heartbeatInterval must be positive");
        }

        this.activeWindow = effectiveWindow.toNanos();
        this.watchDog = new ResponseWatchDog(this::heartbeat, effectiveHeartbeat);
    }

    @Override
    public void onStreamStart(StreamingHandler streamingHandler) {
        if (rejectDelta(streamingHandler)) {
            return;
        }
        this.lastActivityTime.set(System.nanoTime());

        this.watchDog.start();
    }

    @Override
    public void onPartialResponse(String partialResponse, StreamingHandler streamingHandler) {
        if (rejectDelta(streamingHandler)) return;
        ping();
        int offset = responseOffset;
        responseOffset += partialResponse.length();

        this.agentEventPublisher.onPartialText(
                AgentPartialTextEvent.builder()
                        .content(partialResponse)
                        .offset(offset)
                        .responseId(streamingResponseContext.responseId())
                        .agentId(streamingResponseContext.agentId())
                        .executionId(streamingResponseContext.executionId())
                        .metaData(streamingResponseContext.eventMetaData())
                        .build()
        );
    }


    @Override
    public void onPartialThinking(String partialThinking, StreamingHandler streamingHandler) {
        if (rejectDelta(streamingHandler)) return;
        int offset = thinkingOffset;
        ping();
        thinkingOffset += partialThinking.length();

        this.agentEventPublisher.onPartialThinking(
                AgentPartialThinkingEvent.builder()
                        .responseId(streamingResponseContext.responseId())
                        .agentId(streamingResponseContext.agentId())
                        .executionId(streamingResponseContext.executionId())
                        .metaData(streamingResponseContext.eventMetaData())
                        .content(partialThinking)
                        .offset(offset)
                        .build()
        );
    }

    /**
     * A streamed tool call's arguments carry no text, but they are still part of the same stream: a
     * control request arriving while they are being emitted has to cut the stream here too,
     * otherwise the loop would only notice once the whole call had been read — which is exactly the
     * wait this handler exists to avoid. Nothing is published: the assembled call arrives with the
     * round's final response.
     */
    @Override
    public void onPartialToolCall(int index, String id, String name, String partialArguments, StreamingHandler streamingHandler) {
        if (rejectDelta(streamingHandler)) {
            return;
        }
        ping();
        if (id != null && !id.isEmpty() && name != null && !this.hasSentPartialToolCall.contains(id)) {
            this.runtimeEventPublisher.onToolCallPreparing(ToolCallPrepareEvent.builder()
                    .executionId(streamingResponseContext.executionId())
                    .responseId(streamingResponseContext.responseId())
                    .metaData(streamingResponseContext.eventMetaData())
                    .index(index)
                    .agentId(streamingResponseContext.agentId())
                    .partialArguments(partialArguments)
                    .toolCallId(id)
                    .toolName(name)
                    .build());
            this.hasSentPartialToolCall.add(id);
        }


    }

    @Override
    public void onError(Throwable error) {
        if (!reject.compareAndSet(false, true)) return;
        ping();
        try {
            cancelTransport();
            this.watchDog.stop();
        } finally {
            log.error("error has happened at the process: executionId={}", streamingResponseContext.executionId(), error);
            streamingResponseContext.future().completeExceptionally(error);
        }
    }

    @Override
    public void onFinalResponse(ChatResponseEntity completeResponse) {
        if (!reject.compareAndSet(false, true)) return;
        ping();

        try {
            this.watchDog.stop();
            // A received response incurs usage even if control or event publication prevents delivery.
            if (streamingResponseContext.invocationUsage() != null) {
                streamingResponseContext.invocationUsage().recordUsage(streamingResponseContext.responseId(),
                        completeResponse.getTokenUsage());
            }
            ExecutionInterruptedException.Kind kind = resolveInterruption();
            if (kind != null) {
                completeInterruption(kind);
                return;
            }
            completeResponse.setResponseId(streamingResponseContext.responseId());
            this.agentEventPublisher.onCompleteText(
                    AgentCompleteTextEvent.builder()
                            .agentId(streamingResponseContext.agentId())
                            .responseId(streamingResponseContext.responseId())
                            .executionId(streamingResponseContext.executionId())
                            .metaData(streamingResponseContext.eventMetaData())
                            .content(completeResponse.getAiMessageEntity().getText())
                            .meta(completeResponse.getMeta())
                            .build()
            );

            streamingResponseContext.future().complete(completeResponse);

        } catch (RuntimeException | Error error) {
            try {
                cancelTransport();
            } finally {
                streamingResponseContext.future().completeExceptionally(error);
            }
            throw error;
        }
    }


    private boolean rejectDelta(StreamingHandler streamingHandler) {
        transport.set(streamingHandler);
        if (!interruptIfRequested()) return false;
        cancelTransport();
        return true;
    }


    private void heartbeat() {
        try {
            if (this.reject.get()) {
                this.watchDog.stop();
                return;
            }
            ExecutionInterruptedException.Kind kind = resolveInterruption();
            if (kind != null && this.reject.compareAndSet(false, true)) {
                try {
                    this.watchDog.stop();
                    cancelTransportAsync();
                } finally {
                    this.streamingResponseContext.future().completeExceptionally(
                            new ExecutionInterruptedException(kind,
                                    "execution interrupted mid-stream: " + kind.name().toLowerCase()));
                }
                return;
            }
            long idleNanos = System.nanoTime() - lastActivityTime.get();
            if (idleNanos > this.activeWindow && this.reject.compareAndSet(false, true)) {
                try {
                    this.watchDog.stop();
                    cancelTransportAsync();
                } finally {
                    this.streamingResponseContext.future()
                            .completeExceptionally(new ResponseTimeoutException(buildTimeoutMessage(idleNanos), null));
                }
            }
        } catch (Throwable te) {
            log.error("The heartbeat failed to work: {} ", this.streamingResponseContext.executionId(), te);
        }
    }

    private String buildTimeoutMessage(long idleNanos) {
        long idleMillis = TimeUnit.NANOSECONDS.toMillis(idleNanos);
        long windowMillis = TimeUnit.NANOSECONDS.toMillis(this.activeWindow);
        return String.format(
                "Streaming response idle timeout after %d ms (activeWindow=%d ms, executionId=%s, responseId=%s)",
                idleMillis,
                windowMillis,
                streamingResponseContext.executionId(),
                streamingResponseContext.responseId());
    }



    private void ping() {
        lastActivityTime.accumulateAndGet(System.nanoTime(), Math::max);
    }









    /**
     * Aborts the pending stream when the execution has been asked to stop.
     *
     * <p>The attempt must reject callbacks before its future wakes the invoker; otherwise a retry
     * can start while the previous attempt still publishes events.</p>
     *
     * @return {@code true} when the stream was aborted and the current delta must be dropped
     */
    boolean interruptIfRequested() {
        if (reject.get()) return true;

        ExecutionInterruptedException.Kind kind = resolveInterruption();

        if (kind == null) return reject.get();

        if (reject.compareAndSet(false, true)) {
            this.watchDog.stop();
            completeInterruption(kind);
        }

        return true;
    }


    void interrupt(ExecutionInterruptedException.Kind kind) {
        if (reject.compareAndSet(false, true)) {
            this.watchDog.stop();
            completeInterruption(kind);
        }
    }

    private ExecutionInterruptedException.Kind resolveInterruption() {
        if (Thread.currentThread().isInterrupted()) return ExecutionInterruptedException.Kind.CANCEL;
        if (control == null) return null;
        return control.isCancelRequired() ? ExecutionInterruptedException.Kind.CANCEL
                : control.isSuspendRequired() ? ExecutionInterruptedException.Kind.SUSPEND : null;
    }

    private void completeInterruption(ExecutionInterruptedException.Kind kind) {
        try {
            cancelTransport();
        } finally {
            streamingResponseContext.future().completeExceptionally(
                    new ExecutionInterruptedException(kind,
                            "execution interrupted mid-stream: " + kind.name().toLowerCase()));
        }
    }


    /**
     * Transport cleanup must not replace the attempt's original failure.
     */
    private void cancelTransport() {
        StreamingHandler handle = transport.getAndSet(null);
        if (handle == null) return;
        try {
            handle.cancel();
        } catch (RuntimeException error) {
            log.warn("failed to cancel the model response: executionId={}", streamingResponseContext.executionId(), error);
        }
    }

    /**
     * Offloads transport cancellation from the shared watchdog scheduler thread so a blocking
     * stream close cannot stall timeout checks for other concurrent executions.
     */
    private void cancelTransportAsync() {
        try {
            CANCEL_EXECUTOR.execute(this::cancelTransport);
        } catch (RuntimeException error) {
            log.warn("failed to schedule async transport cancellation: executionId={}",
                    streamingResponseContext.executionId(), error);
        }
    }
}
