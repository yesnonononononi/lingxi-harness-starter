package com.summit.core.model.image;

import com.summit.core.agent.Image;
import com.summit.core.conversation.api.ChatResponseEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ImageEditOptionsTest {
    @Test
    void legacyEditsAcceptEmptyOptionsButNeverSilentlyDropOverrides() {
        Image image = Image.from("aW1hZ2U=");
        Image mask = Image.from("bWFzaw==");
        ChatResponseEntity response = ChatResponseEntity.builder().build();
        ImageModel model = new ImageModel() {
            @Override public ChatResponseEntity generate(String prompt) { throw new AssertionError("edit expected"); }
            @Override public ChatResponseEntity edit(Image input, String prompt) {
                assertSame(image, input);
                assertEquals("polish", prompt);
                return response;
            }
            @Override public ChatResponseEntity edit(Image input, Image inputMask, String prompt) {
                assertSame(image, input);
                assertSame(mask, inputMask);
                assertEquals("polish", prompt);
                return response;
            }
        };
        for (ImageGenerationOptions options : new ImageGenerationOptions[]{null, ImageGenerationOptions.EMPTY,
                new ImageGenerationOptions(null, null, 1)}) {
            assertSame(response, model.edit(image, "polish", options));
            assertSame(response, model.edit(image, mask, "polish", options));
        }
        for (ImageGenerationOptions options : new ImageGenerationOptions[]{
                new ImageGenerationOptions("1536x1024", null, 1),
                new ImageGenerationOptions(null, "high", 1), new ImageGenerationOptions(null, null, 2)}) {
            assertThrows(UnsupportedOperationException.class, () -> model.edit(image, "polish", options));
            assertThrows(UnsupportedOperationException.class, () -> model.edit(image, mask, "polish", options));
        }
    }
}
