package com.summit.adapter.langchain4j.model;

import com.summit.core.agent.Image;
import com.summit.core.conversation.api.ChatResponseEntity;
import dev.langchain4j.model.openai.OpenAiImageTokenUsage;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ImageModelAdapterTest {
    private static final dev.langchain4j.data.image.Image OUTPUT = dev.langchain4j.data.image.Image.builder()
            .url(URI.create("https://images.example/result"))
            .base64Data("cGljdHVyZQ==").mimeType("image/webp").revisedPrompt("revised").build();

    @Test
    void preservesImagesFinishReasonAndGenericUsage() {
        ImageModelAdapter adapter = new ImageModelAdapter(prompt -> {
            assertEquals("draw", prompt);
            return Response.from(OUTPUT, new TokenUsage(10, 20, 30), FinishReason.STOP);
        });
        ChatResponseEntity response = adapter.generate("draw");
        Image image = response.getMeta().getImages().getFirst();
        assertEquals(OUTPUT.url(), image.getUrl());
        assertEquals(OUTPUT.base64Data(), image.getBase64Data());
        assertEquals("image/webp", image.mimeType());
        assertEquals("revised", image.getRevisedPrompt());
        assertEquals(Image.DetailLevel.AUTO, image.detailLevel());
        assertEquals(ChatResponseEntity.FinishReason.STOP, response.getMeta().getFinishReason());
        assertEquals(30, response.getTokenUsage().getTotalTokens());
        assertEquals(10, response.getTokenUsage().getInputTokens().getInputTokens());
        assertEquals(20, response.getTokenUsage().getOutputTokens().getOutputTokens());
    }

    @Test
    void batchAndBothEditsDelegateAndKeepAllResults() {
        var second = dev.langchain4j.data.image.Image.builder().base64Data("c2Vjb25k").build();
        Image input = Image.from("aW5wdXQ=", "image/jpeg", "original");
        Image mask = Image.from("bWFzaw==");
        ImageModelAdapter adapter = new ImageModelAdapter(new dev.langchain4j.model.image.ImageModel() {
            public Response<dev.langchain4j.data.image.Image> generate(String prompt) {
                throw new AssertionError("single generation must not be used");
            }
            public Response<List<dev.langchain4j.data.image.Image>> generate(String prompt, int n) {
                assertEquals("batch", prompt);
                assertEquals(2, n);
                return Response.from(List.of(OUTPUT, second));
            }
            public Response<dev.langchain4j.data.image.Image> edit(dev.langchain4j.data.image.Image image, String prompt) {
                assertEquals(input.getBase64Data(), image.base64Data());
                assertEquals("image/jpeg", image.mimeType());
                assertEquals("original", image.revisedPrompt());
                assertEquals("edit", prompt);
                return Response.from(OUTPUT);
            }
            public Response<dev.langchain4j.data.image.Image> edit(dev.langchain4j.data.image.Image image,
                    dev.langchain4j.data.image.Image imageMask, String prompt) {
                assertEquals(input.getBase64Data(), image.base64Data());
                assertEquals(mask.getBase64Data(), imageMask.base64Data());
                assertEquals("image/png", imageMask.mimeType());
                assertEquals("masked", prompt);
                return Response.from(second);
            }
        });
        var batch = adapter.generate("batch", 2);
        assertEquals(2, batch.getMeta().getImages().size());
        assertEquals("c2Vjb25k", batch.getMeta().getImages().get(1).getBase64Data());
        assertEquals(0, batch.getTokenUsage().getTotalTokens());
        assertEquals("revised", adapter.edit(input, "edit").getMeta().getImages().getFirst().getRevisedPrompt());
        assertEquals("c2Vjb25k", adapter.edit(input, mask, "masked").getMeta().getImages().getFirst().getBase64Data());
        assertThrows(IllegalArgumentException.class, () -> adapter.generate("batch", 0));
        assertThrows(IllegalArgumentException.class, () -> adapter.generate("batch", -1));
    }

    @Test
    void imageTokenBreakdownsAreSubsetsOfAggregateCounts() {
        var usage = OpenAiImageTokenUsage.builder().inputTokenCount(15).outputTokenCount(80).totalTokenCount(95)
                .inputTokensDetails(OpenAiImageTokenUsage.TokensDetails.builder().textTokens(5).imageTokens(10).build())
                .outputTokensDetails(OpenAiImageTokenUsage.TokensDetails.builder().imageTokens(80).build()).build();
        var result = new ImageModelAdapter(prompt -> Response.from(OUTPUT, usage)).generate("draw").getTokenUsage();
        assertEquals(95, result.getTotalTokens());
        assertEquals(15, result.getInputTokens().getInputTokens());
        assertEquals(10, result.getInputTokens().getImageTokens());
        assertEquals(80, result.getOutputTokens().getOutputTokens());
        assertEquals(80, result.getOutputTokens().getImageTokens());
    }

    @Test
    void absentCountsAndPartialImageDetailsDoNotFail() {
        for (TokenUsage usage : new TokenUsage[]{null, new TokenUsage(null, null, null),
                OpenAiImageTokenUsage.builder().build(),
                OpenAiImageTokenUsage.builder().inputTokensDetails(OpenAiImageTokenUsage.TokensDetails.builder().build())
                        .outputTokensDetails(OpenAiImageTokenUsage.TokensDetails.builder().build()).build()}) {
            var result = new ImageModelAdapter(prompt -> Response.from(OUTPUT, usage)).generate("draw");
            assertEquals(0, result.getTokenUsage().getTotalTokens());
            assertEquals(0, result.getTokenUsage().getInputTokens().getInputTokens());
            assertEquals(0, result.getTokenUsage().getOutputTokens().getOutputTokens());
            assertEquals(0, result.getTokenUsage().getInputTokens().getImageTokens());
            assertEquals(0, result.getTokenUsage().getOutputTokens().getImageTokens());
        }
    }

    @Test
    void unsupportedDelegateOperationsRemainUnsupported() {
        ImageModelAdapter adapter = new ImageModelAdapter(prompt -> Response.from(OUTPUT));
        assertThrows(IllegalArgumentException.class, () -> adapter.generate("draw", 2));
        assertThrows(IllegalArgumentException.class, () -> adapter.edit(Image.from("eA=="), "edit"));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.edit(Image.from("eA=="), Image.from("eA=="), "edit"));
    }
}
