package com.summit.core.model.image;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Image;
import com.summit.core.conversation.api.ChatResponseEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ImageOutputOptionsTest {
    @Test
    void outputOptionsPersistAndInheritWithoutChangingLegacyPayloads() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ImageGenerationOptions options = new ImageGenerationOptions("1024x1536", "high", 1, "transparent", "png");
        AgentRequest request = AgentRequest.builder().imageGenerationOptions(options).build();
        assertEquals(options, mapper.readValue(mapper.writeValueAsBytes(request), AgentRequest.class).getImageGenerationOptions());
        assertEquals(new ImageGenerationOptions("1024x1024", null, 1),
                mapper.readValue("{\"size\":\"1024x1024\",\"count\":1}", ImageGenerationOptions.class));
        assertEquals(options, ImageGenerationOptions.EMPTY.withDefaults(options));
        assertEquals(new ImageGenerationOptions("1024x1536", "high", 1, "opaque", "jpeg"),
                new ImageGenerationOptions(null, null, null, "opaque", "jpeg").withDefaults(options));
        assertThrows(IllegalArgumentException.class, () -> new ImageGenerationOptions(null, null, null, " ", null));
        assertThrows(IllegalArgumentException.class, () -> new ImageGenerationOptions(null, null, null, null, " "));
    }

    @Test
    void legacyProvidersRejectOutputOverridesForEveryInvocationShape() {
        ImageModel model = prompt -> ChatResponseEntity.builder().build();
        Image image = Image.from("aW1hZ2U=");
        for (ImageGenerationOptions options : new ImageGenerationOptions[]{
                new ImageGenerationOptions(null, null, 1, "transparent", null),
                new ImageGenerationOptions(null, null, 1, null, "png")}) {
            assertThrows(UnsupportedOperationException.class, () -> model.generate("draw", options));
            assertThrows(UnsupportedOperationException.class, () -> model.edit(image, "edit", options));
            assertThrows(UnsupportedOperationException.class, () -> model.edit(image, image, "edit", options));
        }
    }
}
