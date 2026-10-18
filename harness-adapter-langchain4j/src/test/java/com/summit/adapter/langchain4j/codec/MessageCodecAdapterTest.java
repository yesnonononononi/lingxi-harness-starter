package com.summit.adapter.langchain4j.codec;

import com.summit.core.agent.Image;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.conversation.message.TokenUsageEntity;
import com.summit.core.conversation.message.ToolMessageEntity;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class MessageCodecAdapterTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void convertsTextOnlyToolOutputAndPreservesErrorStatus(boolean error) {
        ToolMessageEntity source = ToolMessageEntity.builder().id("call-1").name("lookup")
                .toolOutput("tool result").isError(error).build();

        ToolExecutionResultMessage converted = assertInstanceOf(ToolExecutionResultMessage.class,
                new MessageCodecAdapter().toFramework(source));

        assertEquals("call-1", converted.id());
        assertEquals("lookup", converted.toolName());
        assertEquals("tool result", converted.text());
        assertEquals(1, converted.contents().size());
        assertEquals(error, converted.isError());
    }


    @Test
    void preservesOpenAiUsageDetailsWithoutAddingSubcountsToTotals() {
        assertUsage(OpenAiTokenUsage.builder().inputTokenCount(100).outputTokenCount(30).totalTokenCount(130)
                .inputTokensDetails(OpenAiTokenUsage.InputTokensDetails.builder().cachedTokens(60).build())
                .outputTokensDetails(OpenAiTokenUsage.OutputTokensDetails.builder().reasoningTokens(12).build())
                .build(), 130, 100, 30, 60, 12);
    }

    @Test
    void acceptsOpenAiUsageWithoutOptionalDetails() {
        assertUsage(OpenAiTokenUsage.builder().inputTokenCount(100).outputTokenCount(30).totalTokenCount(130)
                .build(), 130, 100, 30, 0, 0);
    }

    @Test
    void acceptsNullOpenAiCountsAndNullDetailCounts() {
        assertUsage(OpenAiTokenUsage.builder()
                .inputTokensDetails(OpenAiTokenUsage.InputTokensDetails.builder().build())
                .outputTokensDetails(OpenAiTokenUsage.OutputTokensDetails.builder().build())
                .build(), 0, 0, 0, 0, 0);
    }

    @Test
    void acceptsGenericAndMissingUsage() {
        assertUsage(new TokenUsage(100, 30, 130), 130, 100, 30, 0, 0);
        assertUsage(new TokenUsage(null, null, null), 0, 0, 0, 0, 0);
        assertUsage(null, 0, 0, 0, 0, 0);
    }

    private void assertUsage(TokenUsage usage, int total, int input, int output, int cache, int reasoning) {
        TokenUsageEntity converted = new OpenAiMessageCodecAdapter().toChatResponseEntity(ChatResponse.builder()
                .aiMessage(AiMessage.from("answer"))
                .metadata(ChatResponseMetadata.builder().tokenUsage(usage).build()).build()).getTokenUsage();
        assertEquals(total, converted.getTotalTokens());
        assertEquals(input, converted.getInputTokens().getInputTokens());
        assertEquals(output, converted.getOutputTokens().getOutputTokens());
        assertEquals(cache, converted.getInputTokens().getCacheHitTokens());
        assertEquals(reasoning, converted.getOutputTokens().getReasoningTokens());
    }

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
