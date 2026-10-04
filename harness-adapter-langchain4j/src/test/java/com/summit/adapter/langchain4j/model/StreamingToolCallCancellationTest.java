package com.summit.adapter.langchain4j.model;

import com.summit.core.conversation.api.ChatRequestEntity;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.model.streaming.StreamingChatResponseHandler;
import com.summit.core.model.streaming.StreamingHandler;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 非 thinking 链路上，取消必须能被观察到 —— 无论当前到达的是文本 delta 还是工具调用 delta。
 *
 * <p>langchain4j 把文本 delta 路由到 {@code onPartialResponse}、工具调用 delta 路由到
 * {@code onPartialToolCall}，两者各自带着一个 {@code StreamingHandle}。而工具调用轮次里模型只吐
 * {@code tool_calls}、不吐 {@code content}，所以少了后者的钩子，取消就只能等该轮流读完才被发现 ——
 * 那正是「强制断开」来不及发生的场景。</p>
 */
class StreamingToolCallCancellationTest {

    @Test
    void textDeltaReachesTheCoreHandlerAndCanCancel() {
        RecordingHandle handle = new RecordingHandle();
        AtomicBoolean coreReceivedDelta = new AtomicBoolean();

        StreamingChatModelAdapter adapter =
                new StreamingChatModelAdapter(textDelegate(handle, "hello"));

        adapter.chat(request(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String text, StreamingHandler streamingHandler) {
                coreReceivedDelta.set(true);
                streamingHandler.cancel();
            }

            @Override
            public void onPartialThinking(String thinking, StreamingHandler streamingHandler) {
            }

            @Override
            public void onFinalResponse(ChatResponseEntity response) {
            }

            @Override
            public void onError(Throwable err) {
            }
        });

        assertTrue(coreReceivedDelta.get(), "文本 delta 必须到达 core handler");
        assertEquals(1, handle.cancelCount(), "core 侧的 cancel() 必须落到 langchain4j 句柄上");
    }

    @Test
    void toolCallDeltaReachesTheCoreHandlerAndCanCancel() {
        RecordingHandle handle = new RecordingHandle();
        AtomicBoolean coreReceivedDelta = new AtomicBoolean();

        StreamingChatModelAdapter adapter =
                new StreamingChatModelAdapter(toolCallDelegate(handle));

        adapter.chat(request(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String text, StreamingHandler streamingHandler) {
            }

            @Override
            public void onPartialThinking(String thinking, StreamingHandler streamingHandler) {
            }

            @Override
            public void onPartialToolCall(String partialArguments, StreamingHandler streamingHandler) {
                coreReceivedDelta.set(true);
                streamingHandler.cancel();
            }

            @Override
            public void onFinalResponse(ChatResponseEntity response) {
            }

            @Override
            public void onError(Throwable err) {
            }
        });

        assertTrue(coreReceivedDelta.get(), "工具调用 delta 必须到达 core handler");
        assertEquals(1, handle.cancelCount(),
                "工具调用阶段也必须能关掉底层流，否则取消要等整轮读完");
    }

    private static ChatRequestEntity request() {
        return ChatRequestEntity.builder()
                .messages(List.of(UserMessageEntity.from("hi"))).build();
    }

    /** 复刻 langchain4j 对文本 delta 的路由。 */
    private static StreamingChatModel textDelegate(
            dev.langchain4j.model.chat.response.StreamingHandle handle, String text) {
        return new StreamingChatModel() {
            @Override
            public void chat(ChatRequest request,
                             dev.langchain4j.model.chat.response.StreamingChatResponseHandler handler) {
                handler.onPartialResponse(new PartialResponse(text), new PartialResponseContext(handle));
            }
        };
    }

    /** 复刻 langchain4j 对工具调用 delta 的路由（见 ChatCompletionEventDispatcher）。 */
    private static StreamingChatModel toolCallDelegate(
            dev.langchain4j.model.chat.response.StreamingHandle handle) {
        return new StreamingChatModel() {
            @Override
            public void chat(ChatRequest request,
                             dev.langchain4j.model.chat.response.StreamingChatResponseHandler handler) {
                handler.onPartialToolCall(
                        PartialToolCall.builder()
                                .index(0).id("call_1").name("get_weather")
                                .partialArguments("{\"city\"").build(),
                        new PartialToolCallContext(handle));
            }
        };
    }

    private static final class RecordingHandle
            implements dev.langchain4j.model.chat.response.StreamingHandle {
        private int cancelCount;

        @Override
        public void cancel() {
            cancelCount++;
        }

        @Override
        public boolean isCancelled() {
            return cancelCount > 0;
        }

        int cancelCount() {
            return cancelCount;
        }
    }
}
