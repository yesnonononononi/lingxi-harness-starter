package openAi;

import anthropic.ResponseSerializer;
import com.summit.core.agent.Image;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.conversation.message.content.ImageContent;
import com.summit.core.conversation.message.content.TextContent;
import com.summit.core.conversation.message.content.AudioContent;
import com.summit.core.conversation.message.content.VideoContent;
import com.summit.core.conversation.message.content.PdfFileContent;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecuteResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiTokenizerTest {
    private final OpenAiTokenizer tokenizer = new OpenAiTokenizer();

    @Test
    void imageContentEstimatesMatchMessageContributionForEveryDetailLevel() {
        var levels = new Image.DetailLevel[]{Image.DetailLevel.LOW, Image.DetailLevel.MEDIUM,
                Image.DetailLevel.HIGH, Image.DetailLevel.AUTO, Image.DetailLevel.ULTRA_HIGH, null};
        int[] expected = {85, 510, 765, 765, 1105, 765};
        int framing = tokenizer.estimateMessages(List.of(UserMessageEntity.from("")));
        for (int i = 0; i < levels.length; i++) {
            var image = Image.builder().base64Data("unused").detailLevel(levels[i]).build();
            assertEquals(expected[i], tokenizer.estimateContent(ImageContent.from(image)));
            assertEquals(expected[i], tokenizer.estimateMessages(List.of(UserMessageEntity.from(image))) - framing);
        }
    }

    @Test
    void countsTextAndRejectsUnsupportedOrMalformedContentExplicitly() {
        assertEquals(tokenizer.estimateText("hello world"), tokenizer.estimateContent(TextContent.from("hello world")));
        assertThrows(UnsupportedOperationException.class, () -> tokenizer.estimateContent(new AudioContent()));
        assertThrows(UnsupportedOperationException.class, () -> tokenizer.estimateContent(new VideoContent()));
        assertThrows(UnsupportedOperationException.class, () -> tokenizer.estimateContent(new PdfFileContent()));
        assertThrows(NullPointerException.class, () -> tokenizer.estimateContent(new ImageContent(null)));
    }

    @Test
    void countsToolSchemasAndMalformedHistoryWithoutCountingAnthropicReplayTwice() {
        var assistant = AiMessageEntity.builder().thinking("plan").toolCalls(List.of(
                new ToolCallRequest("call_1", "lookup", 0, "{}"),
                new ToolCallRequest("call_2", "lookup", 1, "{\"broken\":"))).build();
        List<Message> history = List.of(UserMessageEntity.from("Look it up"), assistant);
        int baseline = tokenizer.estimateMessages(history);
        assistant.setAttributes(Map.of(ResponseSerializer.NATIVE_CONTENT_ATTRIBUTE,
                List.of(Map.of("type", "thinking", "thinking", "plan", "signature", "opaque".repeat(100)))));
        assertEquals(baseline, tokenizer.estimateMessages(history));

        String schema = "{\"type\":\"object\",\"description\":\"" + "schema description ".repeat(100) + "\"}";
        var lookup = ToolDefinition.builder().id("lookup").name("lookup").description("Find a file")
                .parametersJsonSchema(schema).maxOutput(64).timeout(5L)
                .executor(ignored -> ToolExecuteResult.success("ok")).build();
        assertTrue(tokenizer.estimateMessages(history, List.of(lookup)) - baseline >= tokenizer.estimateText(schema));
    }
}
