package anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a thinking block is bound to, so a stale one is never put back on the wire.
 *
 * <p>Anthropic signs every thinking block against the request that produced it and answers a replay
 * whose prefix differs with {@code Invalid signature in thinking block ... The tools list differs
 * from the one this block was created with} — a 400 that is never retried, so the conversation is
 * over. A tool list does change between rounds in a live session (an MCP server finishes its
 * handshake and publishes more tools), so the fingerprint of model and tools is stored next to the
 * blocks and checked before they are replayed.</p>
 *
 * <p>A block saved by an older version carries no fingerprint, and a signature without provenance
 * can never be proven still valid: it is dropped rather than sent on the chance that the prefix
 * happens to match. Text and tool calls are untouched — the provider's own advice is to remove the
 * block, not the turn.</p>
 */
@Slf4j
final class AnthropicThinkingBinding {

    private static final String FINGERPRINT = "binding";
    private static final String BLOCKS = "blocks";
    private static final String THINKING = "thinking";
    private static final String REDACTED_THINKING = "redacted_thinking";

    private AnthropicThinkingBinding() {
    }

    /**
     * @param model model name the blocks were produced by
     * @param tools tool list exactly as it is sent, cache markers included
     * @return the fingerprint of what the provider binds a thinking block to
     */
    static String fingerprint(String model, JsonNode tools) {
        String material = (model == null ? "" : model) + '\n' + (tools == null ? "" : tools.toString());
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is required to bind thinking blocks", missing);
        }
    }

    /**
     * @param blocks the content blocks the provider returned
     * @param fingerprint fingerprint of the request that produced them
     * @return the attribute value to persist next to the assistant message
     */
    static Map<String, Object> saved(JsonNode blocks, String fingerprint) {
        Map<String, Object> attribute = new LinkedHashMap<>(2);
        attribute.put(FINGERPRINT, fingerprint);
        attribute.put(BLOCKS, blocks);
        return attribute;
    }

    /**
     * The blocks that may be replayed in a request carrying this fingerprint.
     *
     * @return the saved blocks when they are still bound to this request, the same blocks without
     *         their thinking parts when they are not, or {@code null} when nothing usable was saved
     */
    static ArrayNode replayable(Object attribute, String fingerprint, ObjectMapper mapper) {
        if (attribute == null) return null;
        JsonNode saved = attribute instanceof Map<?, ?> map ? mapper.valueToTree(map.get(BLOCKS))
                : mapper.valueToTree(attribute);
        if (!saved.isArray()) return null;
        if (attribute instanceof Map<?, ?> map && fingerprint.equals(map.get(FINGERPRINT))) return (ArrayNode) saved;
        return withoutThinking((ArrayNode) saved, mapper);
    }

    private static ArrayNode withoutThinking(ArrayNode blocks, ObjectMapper mapper) {
        ArrayNode kept = mapper.createArrayNode();
        for (JsonNode block : blocks) {
            String type = block.path("type").asText();
            if (!THINKING.equals(type) && !REDACTED_THINKING.equals(type)) kept.add(block);
        }
        if (kept.size() != blocks.size()) {
            log.warn("Dropping {} thinking block(s) from a replay: the tool list no longer matches the one "
                    + "their signature is bound to", blocks.size() - kept.size());
        }
        return kept;
    }

    private static String hex(byte[] digest) {
        StringBuilder text = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            text.append(Character.forDigit((value >> 4) & 0xF, 16)).append(Character.forDigit(value & 0xF, 16));
        }
        return text.toString();
    }
}
