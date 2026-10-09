package com.summit.core.conversation.message;

import com.summit.core.agent.Image;
import com.summit.core.conversation.message.content.ImageContent;
import com.summit.core.conversation.message.content.TextContent;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserMessageEntityMultiImageTest {
    @Test
    void retainsEveryImageInOrderAfterTheText() {
        Image png = Image.from("cG5n", "image/png");
        Image url = Image.from(URI.create("https://example.com/second.jpg"));
        Image jpeg = Image.from("anBlZw==", "image/jpeg");
        List<Image> images = new ArrayList<>(List.of(png, url, jpeg));
        UserMessageEntity message = UserMessageEntity.from("compare all three", images);
        images.clear();

        assertEquals(4, message.getContent().size());
        assertEquals("compare all three", assertInstanceOf(TextContent.class, message.getContent().getFirst()).getText());
        assertSame(png, assertInstanceOf(ImageContent.class, message.getContent().get(1)).getImage());
        assertSame(url, assertInstanceOf(ImageContent.class, message.getContent().get(2)).getImage());
        assertSame(jpeg, assertInstanceOf(ImageContent.class, message.getContent().get(3)).getImage());
        assertEquals(MessageType.USER, message.type());
    }

    @Test
    void emptyImageListRetainsTheTextOnlyMessage() {
        UserMessageEntity message = UserMessageEntity.from("text only", List.<Image>of());
        assertEquals(1, message.getContent().size());
        assertEquals("text only", message.text());
    }

    @Test
    void singleImageOverloadsRemainCompatible() {
        Image image = Image.from("cG5n", "image/png");
        assertEquals(2, UserMessageEntity.from("one image", image).getContent().size());
        assertEquals(1, UserMessageEntity.from(image).getContent().size());
        assertEquals(1, UserMessageEntity.from("text only").getContent().size());
    }

    @Test
    void rejectsNullImageList() {
        assertThrows(NullPointerException.class, () -> UserMessageEntity.from("text", (List<Image>) null));
    }
}
