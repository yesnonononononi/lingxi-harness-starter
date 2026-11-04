package mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import mcp.json.Jackson2McpJsonMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The adapter must be a transparent forward: what the SDK writes with it has to be readable by the
 * same mapper, including the records the protocol is made of and the parameterized types the
 * ServiceLoader-based default would otherwise handle.
 */
class Jackson2McpJsonMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Jackson2McpJsonMapper mapper = new Jackson2McpJsonMapper(objectMapper);

    @Test
    void serializesARecordAndReadsItBack() throws IOException {
        McpSchema.Tool tool = McpSchema.Tool.builder("search", Map.of("type", "object"))
                .description("Search things")
                .build();

        String json = mapper.writeValueAsString(tool);
        McpSchema.Tool restored = mapper.readValue(json, McpSchema.Tool.class);

        assertEquals("search", restored.name());
        assertEquals("Search things", restored.description());
        assertEquals(tool.inputSchema(), restored.inputSchema());
        assertTrue(json.contains("\"inputSchema\""));
    }

    @Test
    void readsBytesAndStringsTheSameWay() throws IOException {
        Map<String, Object> payload = Map.of("a", 1, "b", List.of("x", "y"));

        byte[] bytes = mapper.writeValueAsBytes(payload);

        assertEquals(payload, mapper.readValue(bytes, Map.class));
        assertEquals(payload, mapper.readValue(new String(bytes, StandardCharsets.UTF_8), Map.class));
    }

    /** Parameterized types are exactly what {@link TypeRef} exists for. */
    @Test
    void readsAParameterizedTypeThroughATypeReference() throws IOException {
        List<Map<String, Object>> tools = List.of(Map.of("name", "search"), Map.of("name", "echo"));
        String json = mapper.writeValueAsString(tools);

        List<Map<String, Object>> restored = mapper.readValue(json, new TypeRef<List<Map<String, Object>>>() {
        });
        assertEquals(2, restored.size());
        assertEquals("echo", restored.get(1).get("name"));

        byte[] bytes = mapper.writeValueAsBytes(tools);
        assertEquals(2, mapper.readValue(bytes, new TypeRef<List<Map<String, Object>>>() {
        }).size());
    }

    @Test
    void convertsAlreadyParsedTrees() {
        Object parsed = objectMapper.valueToTree(Map.of("protocolVersion", "2025-11-25"));

        McpSchema.InitializeResult result = mapper.convertValue(Map.of(
                "protocolVersion", "2025-11-25",
                "capabilities", Map.of(),
                "serverInfo", Map.of("name", "fake", "version", "1.0")), McpSchema.InitializeResult.class);

        assertEquals("2025-11-25", result.protocolVersion());
        assertEquals("fake", result.serverInfo().name());
        assertNotNull(parsed);

        Map<?, ?> back = mapper.convertValue(result, new TypeRef<Map<String, Object>>() {
        });
        assertEquals("2025-11-25", back.get("protocolVersion"));
    }

    /** The SDK materializes absent members as empty values; that must not fail here either. */
    @Test
    void toleratesMissingOptionalMembers() throws IOException {
        McpSchema.Tool tool = mapper.readValue("{\"name\":\"search\",\"inputSchema\":{\"type\":\"object\"}}",
                McpSchema.Tool.class);

        assertEquals("search", tool.name());
        assertNull(tool.description());
        assertFalse(tool.inputSchema().isEmpty());
    }
}
