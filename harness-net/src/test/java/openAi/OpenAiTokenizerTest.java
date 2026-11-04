package openAi;

import anthropic.ResponseSerializer;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecuteResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiTokenizerTest {
    private final OpenAiTokenizer tokenizer = new OpenAiTokenizer();

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
