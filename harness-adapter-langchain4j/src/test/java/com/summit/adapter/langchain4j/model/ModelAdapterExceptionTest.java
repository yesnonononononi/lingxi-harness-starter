package com.summit.adapter.langchain4j.model;

import com.summit.core.conversation.api.ChatRequestEntity;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.exception.ModelException;
import com.summit.core.exception.NonRetriableModelException;
import com.summit.core.exception.RetriableModelException;
import com.summit.core.model.streaming.StreamingHandler;
import com.summit.core.runtime.loop.suspension.ExecutionInterruptedException;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.LangChain4jException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.RetriableException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ProtocolException;
import java.security.cert.CertificateException;
import java.io.InterruptedIOException;
import java.io.EOFException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedByInterruptException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLException;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelAdapterExceptionTest {
    @TestFactory
    Stream<DynamicTest> classifiedFailuresAtEveryChatBoundary() {
        List<FailureCase> failures = List.of(
                new FailureCase("retriable", new RetriableException("temporary"), RetriableModelException.class),
                new FailureCase("rate-limit", new RateLimitException("limited"), RetriableModelException.class),
                new FailureCase("timeout", new TimeoutException("slow"), RetriableModelException.class),
                new FailureCase("server", new InternalServerException("unavailable"), RetriableModelException.class),
                new FailureCase("non-retriable", new NonRetriableException("permanent"), NonRetriableModelException.class),
                new FailureCase("authentication", new AuthenticationException("denied"), NonRetriableModelException.class),
                new FailureCase("request", new InvalidRequestException("invalid"), NonRetriableModelException.class),
                new FailureCase("model", new ModelNotFoundException("missing"), NonRetriableModelException.class));
        return Stream.of(Boundary.values()).flatMap(boundary -> failures.stream().flatMap(failure ->
                Stream.of(false, true).map(wrapped -> DynamicTest.dynamicTest(
                        boundary + " " + failure.name() + (wrapped ? " wrapped" : " direct"), () -> {
                            RuntimeException original = wrapped
                                    ? new CompletionException(new ExecutionException(failure.error())) : failure.error();
                            ModelException converted = assertInstanceOf(failure.expected(), invoke(boundary, original));
                            assertEquals(failure.error().getMessage(), converted.getMessage());
                            assertSame(original, converted.getCause());
                        }))));
    }

    @TestFactory
    Stream<DynamicTest> unknownAndControlFailuresRemainUnchanged() {
        List<RuntimeException> failures = List.of(
                new IllegalStateException("unexpected"),
                new LangChain4jException(new SSLHandshakeException("Remote host terminated the handshake")
                        .initCause(new CertificateException("certificate rejected"))),
                new LangChain4jException(new SSLException("certificate rejected",
                        new CertificateException("certificate rejected"))),
                new CompletionException(new InterruptedIOException("interrupted")),
                new LangChain4jException(new ClosedByInterruptException()),
                new LangChain4jException(new IOException("cancelled", new CancellationException("cancelled"))),
                new LangChain4jException(new IOException("interrupted", new InterruptedException("interrupted"))),
                new RuntimeException(new RateLimitException("not an asynchronous wrapper")),
                new CancellationException("cancelled"),
                new ExecutionInterruptedException(ExecutionInterruptedException.Kind.CANCEL, "cancelled"),
                new CompletionException(new ExecutionInterruptedException(ExecutionInterruptedException.Kind.SUSPEND, "suspended")),
                new ModelException("unclassified"),
                new RetriableModelException("already classified", new IOException("temporary")),
                new NonRetriableModelException("already classified", new IllegalArgumentException("invalid")));
        return Stream.of(Boundary.values()).flatMap(boundary -> failures.stream().map(failure ->
                DynamicTest.dynamicTest(boundary + " preserves " + failure.getClass().getSimpleName()
                        + ": " + failure.getMessage(), () -> assertSame(failure, invoke(boundary, failure)))));
    }

    @TestFactory
    Stream<DynamicTest> transportFailuresAreClassifiedAtEveryChatBoundary() {
        SSLHandshakeException shutdown = new SSLHandshakeException("SSL peer shut down incorrectly");
        shutdown.initCause(new EOFException("closed"));
        List<IOException> failures = List.of(new IOException("HTTP/1.1 header parser received no bytes"),
                new EOFException("closed"), new SocketException("connection reset"),
                new SocketTimeoutException("read timed out"), new UnknownHostException("temporary DNS failure"),
                new HttpTimeoutException("request timed out"), new ClosedChannelException(),
                new SSLHandshakeException("Remote host terminated the handshake"),
                shutdown,
                new ProtocolException("unexpected end of stream"),
                new ProtocolException("malformed HTTP status line"),
                new SSLHandshakeException("handshake failed"),
                new SSLException("bad_record_mac"),
                new IOException("unlisted transport failure") { });
        return Stream.of(Boundary.values()).flatMap(boundary -> failures.stream().flatMap(failure ->
                Stream.of(false, true).map(unchecked -> DynamicTest.dynamicTest(
                        boundary + " transport " + failure.getClass().getSimpleName() + " unchecked=" + unchecked, () -> {
                    RuntimeException wrapped = new CompletionException(new LangChain4jException(
                            unchecked ? new UncheckedIOException(failure) : failure));
                    assertSame(wrapped, assertInstanceOf(RetriableModelException.class, invoke(boundary, wrapped)).getCause());
                }))));
    }

    @Test
    void callbackAcceptsCheckedAsyncWrappersAndKeepsUnknownThrowables() {
        AtomicReference<StreamingChatResponseHandler> bridge = new AtomicReference<>();
        StreamingChatModel delegate = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                bridge.set(handler);
            }
        };
        CaptureHandler target = new CaptureHandler();
        new StreamingChatModelAdapter(delegate).chat(request(), target);
        ExecutionException wrapped = new ExecutionException(new RateLimitException("limited"));
        bridge.get().onError(wrapped);
        assertSame(wrapped, assertInstanceOf(RetriableModelException.class, target.error).getCause());

        IOException unknown = new IOException("closed");
        bridge.get().onError(unknown);
        assertSame(unknown, assertInstanceOf(RetriableModelException.class, target.error).getCause());
        AssertionError fatal = new AssertionError("fatal");
        bridge.get().onError(fatal);
        assertSame(fatal, target.error);
    }

    private static Throwable invoke(Boundary boundary, RuntimeException failure) {
        AtomicInteger calls = new AtomicInteger();
        if (boundary == Boundary.CHAT) {
            ChatModel delegate = new ChatModel() {
                @Override
                public ChatResponse doChat(ChatRequest request) {
                    calls.incrementAndGet();
                    throw failure;
                }
            };
            RuntimeException converted = assertThrows(RuntimeException.class,
                    () -> new ChatModelAdapter(delegate).chat(request()));
            assertEquals(1, calls.get());
            return converted;
        }
        AtomicReference<StreamingChatResponseHandler> bridge = new AtomicReference<>();
        StreamingChatModel delegate = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                calls.incrementAndGet();
                if (boundary == Boundary.STREAM_START) throw failure;
                bridge.set(handler);
            }
        };
        CaptureHandler target = new CaptureHandler();
        StreamingChatModelAdapter adapter = new StreamingChatModelAdapter(delegate);
        if (boundary == Boundary.STREAM_START) {
            RuntimeException converted = assertThrows(RuntimeException.class, () -> adapter.chat(request(), target));
            assertEquals(1, calls.get());
            return converted;
        }
        adapter.chat(request(), target);
        bridge.get().onError(failure);
        assertEquals(1, calls.get());
        return target.error;
    }

    private static ChatRequestEntity request() {
        return ChatRequestEntity.builder().messages(List.of(UserMessageEntity.from("hello"))).tools(List.of()).build();
    }

    private record FailureCase(String name, RuntimeException error, Class<? extends ModelException> expected) {
    }

    private enum Boundary { CHAT, STREAM_START, STREAM_CALLBACK }

    private static final class CaptureHandler implements com.summit.core.model.streaming.StreamingChatResponseHandler {
        private Throwable error;

        @Override
        public void onPartialResponse(String text, StreamingHandler handler) {
        }

        @Override
        public void onPartialThinking(String thinking, StreamingHandler handler) {
        }

        @Override
        public void onFinalResponse(ChatResponseEntity response) {
        }

        @Override
        public void onError(Throwable error) {
            this.error = error;
        }
    }
}
