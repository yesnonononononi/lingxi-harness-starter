package com.summit.core.model.image;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.agent.AgentRequest;
import com.summit.core.conf.ModelConfig;
import com.summit.core.conversation.api.ChatResponseEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ImageGenerationOptionsTest {
    @Test
    void requestSpecificationsSurvivePersistenceAndOldPayloadsRemainReadable() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var options = new ImageGenerationOptions("1536x1024", "high", 2);
        var request = AgentRequest.builder().imageGenerationOptions(options).build();
        assertEquals(options, mapper.readValue(mapper.writeValueAsBytes(request), AgentRequest.class).getImageGenerationOptions());
        assertNull(mapper.readValue("{}", AgentRequest.class).getImageGenerationOptions());
        var oldDefaults = mapper.readValue("{\"size\":\"1024x1024\",\"quality\":\"high\"}", ModelConfig.ImageOptions.class);
        assertNull(oldDefaults.count());
        var defaults = oldDefaults.toBuilder().count(2).build();
        assertEquals(defaults, mapper.readValue(mapper.writeValueAsBytes(defaults), ModelConfig.ImageOptions.class));
    }

    @Test
    void invalidSpecificationsFailBeforeInvocation() {
        assertThrows(IllegalArgumentException.class, () -> new ImageGenerationOptions(" ", null, null));
        assertThrows(IllegalArgumentException.class, () -> new ImageGenerationOptions(null, "", null));
        assertThrows(IllegalArgumentException.class, () -> new ImageGenerationOptions(null, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new ImageGenerationOptions(null, null, -1));
    }

    @Test
    void olderProvidersRetainSingleAndBatchCallsButNeverIgnoreUnsupportedOverrides() {
        ChatResponseEntity response = ChatResponseEntity.builder().build();
        ImageModel single = prompt -> response;
        assertSame(response, single.generate("draw", (ImageGenerationOptions) null));
        assertSame(response, single.generate("draw", ImageGenerationOptions.EMPTY));
        assertSame(response, single.generate("draw", new ImageGenerationOptions(null, null, 1)));
        assertThrows(UnsupportedOperationException.class,
                () -> single.generate("draw", new ImageGenerationOptions("1024x1024", null, null)));
        assertThrows(UnsupportedOperationException.class,
                () -> single.generate("draw", new ImageGenerationOptions(null, "high", null)));
        ImageModel batch = new ImageModel() {
            public ChatResponseEntity generate(String prompt) { throw new AssertionError("batch expected"); }
            public ChatResponseEntity generate(String prompt, int count) {
                assertEquals(2, count);
                return response;
            }
        };
        assertSame(response, batch.generate("draw", new ImageGenerationOptions(null, null, 2)));
    }
}
