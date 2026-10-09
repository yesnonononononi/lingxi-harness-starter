package com.summit.adapter.langchain4j.codec;

import com.summit.core.agent.Image;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.message.UserMessageEntity;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class MessageCodecAdapterTest {

    @Test
    void assignsRequestIndicesFromModelListOrderAndPreservesCallIdentity() {
        List<ToolExecutionRequest> requests = List.of(
                ToolExecutionRequest.builder().id("z").name("search").arguments("{\"q\":1}").build(),
                ToolExecutionRequest.builder().id("a").name("search").arguments("{\"q\":2}").build(),
                ToolExecutionRequest.builder().id("m").name("read").arguments("{}").build());
        MessageCodecAdapter codec = new MessageCodecAdapter();
        ChatResponseEntity converted = codec.toChatResponseEntity(ChatResponse.builder()
                .aiMessage(AiMessage.builder().toolExecutionRequests(requests).build())
                .metadata(ChatResponseMetadata.builder().build()).build());

        List<ToolCallRequest> calls = converted.getAiMessageEntity().getToolCalls();
        assertEquals(List.of(0, 1, 2), calls.stream().map(ToolCallRequest::requestIndex).toList());
        assertEquals(List.of("z", "a", "m"), calls.stream().map(ToolCallRequest::id).toList());
        AiMessage roundTrip = assertInstanceOf(AiMessage.class, codec.toFramework(converted.getAiMessageEntity()));
        assertEquals(requests, roundTrip.toolExecutionRequests());
    }

    @Test
    void convertsImageContentInsteadOfDroppingItFromTheUserMessage() {
        UserMessageEntity source = UserMessageEntity.from(
                "describe this", Image.from("base64-image", "image/jpeg"));

        ChatMessage converted = new MessageCodecAdapter().toFramework(source);

        UserMessage userMessage = assertInstanceOf(UserMessage.class, converted);
        assertEquals(2, userMessage.contents().size());
        dev.langchain4j.data.message.ImageContent imageContent = assertInstanceOf(
                dev.langchain4j.data.message.ImageContent.class, userMessage.contents().get(1));
        assertEquals("base64-image", imageContent.image().base64Data());
        assertEquals("image/jpeg", imageContent.image().mimeType());
    }
}
