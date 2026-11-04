package mcp.json;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;

import java.io.IOException;
import java.util.Objects;

/**
 * Adapts the project's Jackson 2 {@link ObjectMapper} to the SDK's {@link McpJsonMapper}.
 *
 * <p>The SDK ships its default mapper in a separate artifact per Jackson generation, and the
 * aggregate {@code mcp} artifact selects the Jackson 3 one — which this project must not pull in,
 * because the whole code base (and every stored snapshot) is Jackson 2. Injecting this adapter into
 * each transport and into the client specification keeps the SDK on the application's object
 * mapper, including its module registrations and configuration.</p>
 *
 * <p>Every method is a plain forward; the only translation is a {@link TypeRef}'s captured
 * {@link java.lang.reflect.Type} to the Jackson {@link JavaType} the mapper understands.</p>
 */
public final class Jackson2McpJsonMapper implements McpJsonMapper {

    private final ObjectMapper objectMapper;

    /**
     * @param objectMapper mapper to delegate to; must not be null
     */
    public Jackson2McpJsonMapper(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public <T> T readValue(String content, Class<T> type) throws IOException {
        return objectMapper.readValue(content, type);
    }

    @Override
    public <T> T readValue(byte[] content, Class<T> type) throws IOException {
        return objectMapper.readValue(content, type);
    }

    @Override
    public <T> T readValue(String content, TypeRef<T> type) throws IOException {
        return objectMapper.readValue(content, resolveJavaType(type));
    }

    @Override
    public <T> T readValue(byte[] content, TypeRef<T> type) throws IOException {
        return objectMapper.readValue(content, resolveJavaType(type));
    }

    @Override
    public <T> T convertValue(Object fromValue, Class<T> type) {
        return objectMapper.convertValue(fromValue, type);
    }

    @Override
    public <T> T convertValue(Object fromValue, TypeRef<T> type) {
        return objectMapper.convertValue(fromValue, resolveJavaType(type));
    }

    @Override
    public String writeValueAsString(Object value) throws IOException {
        return objectMapper.writeValueAsString(value);
    }

    @Override
    public byte[] writeValueAsBytes(Object value) throws IOException {
        return objectMapper.writeValueAsBytes(value);
    }

    /** Turns the type captured by a {@link TypeRef} into the mapper's own type model. */
    private JavaType resolveJavaType(TypeRef<?> type) {
        Objects.requireNonNull(type, "type must not be null");
        return objectMapper.getTypeFactory().constructType(type.getType());
    }
}
