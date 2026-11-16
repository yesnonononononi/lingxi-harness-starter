package anthropic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.summit.core.exception.NonRetriableModelException;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code input_schema} one tool needs to reach the Anthropic Messages API.
 *
 * <p>That API accepts only a plain object root: a top-level {@code oneOf}, {@code anyOf} or
 * {@code allOf} is answered with a 400 that is never retried, even though the tool itself is
 * perfectly callable. A provider-neutral schema may legitimately be a union — MCP servers publish
 * exactly that — so the branches are folded into a single object here instead of sending a request
 * Anthropic is guaranteed to reject. Nested unions are untouched: the restriction is on the root
 * only.</p>
 *
 * <p>Folding keeps the union's properties. Which of them stay required depends on the keyword: a
 * {@code oneOf}/{@code anyOf} branch applies alone, so only a field every branch demands is mandatory
 * up front; {@code allOf} branches hold simultaneously, so every branch's requirements apply and the
 * demanded names are unioned — intersecting them there would silently drop mandatory arguments.</p>
 */
@Slf4j
final class AnthropicToolSchema {

    private static final String[] UNIONS = {"oneOf", "anyOf", "allOf"};

    private AnthropicToolSchema() {
    }

    /**
     * @param schema tool parameters as published, possibly blank
     * @param mapper mapper that parses the published schema
     * @return the schema to send, always an object root
     */
    static JsonNode inputSchema(String schema, ObjectMapper mapper) throws JsonProcessingException {
        JsonNode node = schema == null || schema.isBlank()
                ? mapper.createObjectNode().put("type", "object")
                : mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(schema);
        if (!node.isObject()) {
            throw new NonRetriableModelException("Anthropic tool schema must be a JSON object", null);
        }
        return foldUnion((ObjectNode) node, mapper);
    }

    private static JsonNode foldUnion(ObjectNode root, ObjectMapper mapper) {
        String keyword = null;
        ArrayNode branches = null;
        for (String candidate : UNIONS) {
            if (root.path(candidate) instanceof ArrayNode found) {
                keyword = candidate;
                branches = found;
                break;
            }
        }
        if (branches == null) return root;
        ObjectNode folded = mapper.createObjectNode().put("type", "object");
        ObjectNode properties = folded.putObject("properties");
        copyProperties(root.path("properties"), properties);
        List<String> required = null;
        boolean everyBranchApplies = "allOf".equals(keyword);
        for (JsonNode branch : branches) {
            copyProperties(branch.path("properties"), properties);
            required = demanded(required, branch.path("required"), everyBranchApplies);
        }
        if (required != null && !required.isEmpty()) {
            ArrayNode names = folded.putArray("required");
            required.forEach(names::add);
        }
        log.warn("Anthropic rejects a top-level {} in a tool schema; its branches are folded into one object", keyword);
        return folded;
    }

    private static void copyProperties(JsonNode source, ObjectNode target) {
        if (source.isObject()) source.fields().forEachRemaining(field -> target.set(field.getKey(), field.getValue()));
    }

    /**
     * @param current           names demanded so far, {@code null} before the first branch
     * @param required          the {@code required} array of this branch
     * @param everyBranchApplies {@code allOf}: requirements accumulate; otherwise only the surviving
     *                           intersection is required
     */
    private static List<String> demanded(List<String> current, JsonNode required, boolean everyBranchApplies) {
        List<String> names = new ArrayList<>();
        for (JsonNode name : required) {
            if (name.isTextual()) names.add(name.asText());
        }
        if (current == null) return names;
        if (!everyBranchApplies) {
            current.retainAll(names);
            return current;
        }
        for (String name : names) {
            if (!current.contains(name)) current.add(name);
        }
        return current;
    }
}
