package anthropic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnthropicThinkingBindingTest {

    private static final String BLOCKS = """
            [{"type":"thinking","thinking":"reason","signature":"signed"},
             {"type":"redacted_thinking","data":"opaque"},
             {"type":"tool_use","id":"call-1","name":"lookup","input":{}}]
            """;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void fingerprintsTheModelAndTheToolsItSends() throws Exception {
        ArrayNode tools = (ArrayNode) mapper.readTree("[{\"name\":\"lookup\"}]");
        String fingerprint = AnthropicThinkingBinding.fingerprint("claude-sonnet-4-6", tools);

        assertEquals(fingerprint, AnthropicThinkingBinding.fingerprint("claude-sonnet-4-6", tools));
        assertNotEquals(fingerprint, AnthropicThinkingBinding.fingerprint("claude-sonnet-4-5", tools));
        assertNotEquals(fingerprint, AnthropicThinkingBinding.fingerprint("claude-sonnet-4-6",
                (ArrayNode) mapper.readTree("[{\"name\":\"lookup\"},{\"name\":\"another\"}]")));
        assertNotEquals(fingerprint, AnthropicThinkingBinding.fingerprint("claude-sonnet-4-6", null));
    }

    @Test
    void replaysTheBlocksWhenTheRequestStillMatches() throws Exception {
        ArrayNode blocks = (ArrayNode) mapper.readTree(BLOCKS);
        String fingerprint = AnthropicThinkingBinding.fingerprint("claude-sonnet-4-6", null);
        Map<String, Object> attribute = AnthropicThinkingBinding.saved(blocks, fingerprint);

        ArrayNode replay = AnthropicThinkingBinding.replayable(attribute, fingerprint, mapper);

        assertEquals(3, replay.size());
        assertEquals("thinking", replay.get(0).path("type").asText());
        assertEquals("signed", replay.get(0).path("signature").asText());
    }

    @Test
    void dropsTheThinkingPartWhenTheFingerprintMoved() throws Exception {
        ArrayNode blocks = (ArrayNode) mapper.readTree(BLOCKS);
        Map<String, Object> attribute = AnthropicThinkingBinding.saved(blocks, "before");

        ArrayNode replay = AnthropicThinkingBinding.replayable(attribute, "after", mapper);

        assertEquals(1, replay.size(), replay.toString());
        assertEquals("tool_use", replay.get(0).path("type").asText());
    }

    @Test
    void dropsTheThinkingPartOfABlockSavedBeforeFingerprintsExisted() throws Exception {
        ArrayNode legacy = (ArrayNode) mapper.readTree(BLOCKS);

        ArrayNode replay = AnthropicThinkingBinding.replayable(legacy, "anything", mapper);

        assertEquals(1, replay.size(), replay.toString());
        assertEquals("tool_use", replay.get(0).path("type").asText());
    }

    @Test
    void ignoresAMessageWithoutSavedBlocks() {
        assertNull(AnthropicThinkingBinding.replayable(null, "anything", mapper));
        assertNull(AnthropicThinkingBinding.replayable(Map.of(), "anything", mapper));
        assertNull(AnthropicThinkingBinding.replayable(Map.of("binding", "before"), "anything", mapper));
    }

    @Test
    void keepsASavedBlockThatOnlyEverCarriedToolCalls() throws Exception {
        Map<String, Object> attribute = AnthropicThinkingBinding.saved(
                (ArrayNode) mapper.readTree("[{\"type\":\"tool_use\",\"id\":\"call-1\",\"input\":{}}]"), "before");

        ArrayNode replay = AnthropicThinkingBinding.replayable(attribute, "after", mapper);

        assertEquals(1, replay.size());
        assertEquals("tool_use", replay.get(0).path("type").asText());
    }
}
