package mcp.json;

import io.modelcontextprotocol.json.schema.JsonSchemaValidator;

import java.util.Map;

/**
 * A {@link JsonSchemaValidator} that accepts everything.
 *
 * <p>Two reasons for it to exist. First, {@code McpClient.SyncSpec} falls back to a ServiceLoader
 * lookup when no validator is injected, and the JSON-schema-validation artifacts are not on the
 * class path — the lookup would fail with a {@code ServiceConfigurationError} inside {@code build()},
 * i.e. during the first connection rather than at configuration time. Second, this client does not
 * validate tool output at all: structured content is passed through to the runtime, which already
 * truncates and reports it.</p>
 *
 * <p>Schema conformance of the tool input schemas is likewise not asserted — servers in the wild
 * publish schemas with vendor extensions, and rejecting them would drop their tools entirely.</p>
 */
public final class PassthroughJsonSchemaValidator implements JsonSchemaValidator {

    @Override
    public ValidationResponse validate(Map<String, Object> schema, Object structuredContent) {
        return ValidationResponse.asValid(null);
    }

    @Override
    public ValidationResponse validateSchema(Map<String, Object> schema) {
        return ValidationResponse.asValid(null);
    }
}
