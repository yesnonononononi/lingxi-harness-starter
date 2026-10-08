package com.summit.adapter.langchain4j.codec;

import com.summit.core.agent.Image;
import com.summit.core.conversation.message.UserMessageEntity;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class MessageCodecAdapterTest {

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
