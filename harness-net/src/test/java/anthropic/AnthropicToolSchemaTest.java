package anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.exception.NonRetriableModelException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AnthropicToolSchemaTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void keepsAnObjectRootUntouched() throws Exception {
        JsonNode schema = AnthropicToolSchema.inputSchema(
                "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}", mapper);

        assertEquals("object", schema.path("type").asText());
        assertEquals("string", schema.at("/properties/q/type").asText());
        assertEquals("q", schema.at("/required/0").asText());
        assertFalse(schema.has("oneOf"));
    }

    @Test
    void foldsTopLevelOneOfIntoOneObject() throws Exception {
        JsonNode schema = AnthropicToolSchema.inputSchema("""
                {"oneOf":[
                  {"type":"object","properties":{"path":{"type":"string"},"depth":{"type":"integer"}},
                   "required":["path","depth"]},
                  {"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}]}
                """, mapper);

        assertEquals("object", schema.path("type").asText());
        assertFalse(schema.has("oneOf"));
        assertEquals("string", schema.at("/properties/path/type").asText());
        assertEquals("integer", schema.at("/properties/depth/type").asText());
        assertEquals("string", schema.at("/properties/url/type").asText());
        assertFalse(schema.has("required"), "no field is demanded by every branch: " + schema);
    }

    @Test
    void foldsAnyOfAndAllOfBranchesIntoTheSameObject() throws Exception {
        JsonNode anyOf = AnthropicToolSchema.inputSchema(
                "{\"anyOf\":[{\"properties\":{\"a\":{\"type\":\"string\"}}}]}", mapper);
        assertEquals("object", anyOf.path("type").asText());
        assertFalse(anyOf.has("anyOf"));
        assertEquals("string", anyOf.at("/properties/a/type").asText());

        JsonNode allOf = AnthropicToolSchema.inputSchema(
                "{\"allOf\":[{\"properties\":{\"b\":{\"type\":\"number\"}}}]}", mapper);
        assertEquals("object", allOf.path("type").asText());
        assertFalse(allOf.has("allOf"));
        assertEquals("number", allOf.at("/properties/b/type").asText());
    }

    @Test
    void keepsAFieldEveryBranchRequires() throws Exception {
        JsonNode schema = AnthropicToolSchema.inputSchema("""
                {"anyOf":[
                  {"properties":{"id":{"type":"string"},"extra":{"type":"string"}},"required":["id","extra"]},
                  {"properties":{"id":{"type":"string"}},"required":["id"]}]}
                """, mapper);

        assertEquals(1, schema.path("required").size(), schema.toString());
        assertEquals("id", schema.at("/required/0").asText());
    }

    @Test
    void keepsThePropertiesDeclaredNextToTheUnion() throws Exception {
        JsonNode schema = AnthropicToolSchema.inputSchema(
                "{\"properties\":{\"always\":{\"type\":\"boolean\"}},\"oneOf\":[{\"properties\":{\"a\":{}}}]}", mapper);

        assertEquals("boolean", schema.at("/properties/always/type").asText());
        assertTrue(schema.at("/properties/a").isObject());
    }

    @Test
    void fallsBackToAnEmptyObjectSchemaWhenNothingIsDeclared() throws Exception {
        assertEquals("object", AnthropicToolSchema.inputSchema("  ", mapper).path("type").asText());
        assertEquals("object", AnthropicToolSchema.inputSchema(null, mapper).path("type").asText());
    }

    @Test
    void rejectsANonObjectRoot() {
        assertThrows(NonRetriableModelException.class,
                () -> AnthropicToolSchema.inputSchema("[]", mapper));
        assertThrows(NonRetriableModelException.class,
                () -> AnthropicToolSchema.inputSchema("\"text\"", mapper));
    }

    @Test
    void leavesANestedUnionAlone() throws Exception {
        JsonNode schema = AnthropicToolSchema.inputSchema(
                "{\"type\":\"object\",\"properties\":{\"q\":{\"oneOf\":[{\"type\":\"string\"}]}}}", mapper);

        assertTrue(schema.at("/properties/q/oneOf").isArray());
    }
}
