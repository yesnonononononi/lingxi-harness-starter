package anthropic;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.summit.core.agent.Image;
import com.summit.core.conf.ModelConfig;
import com.summit.core.conversation.api.ChatRequestEntity;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.conversation.message.ToolMessageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.conversation.message.content.ImageContent;
import com.summit.core.conversation.message.content.TextContent;
import com.summit.core.exception.NonRetriableModelException;
import com.summit.core.tool.ToolDefinition;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnthropicModelRequest {
    private String model;
    @JsonProperty("max_tokens")
    private int maxTokens;
    private ArrayNode system;
    private List<Message> messages;
    private ArrayNode tools;
    private ObjectNode thinking;
    @JsonProperty("output_config")
    private ObjectNode outputConfig;
    @JsonProperty("cache_control")
    private ObjectNode cacheControl;
    @Builder.Default
    private boolean stream = false;

    @AllArgsConstructor
    @NoArgsConstructor
    @Data
    private static class Message {
        private String role;
        private ArrayNode content;
    }

    static AnthropicModelRequest from(ChatRequestEntity request, ModelConfig config, ObjectMapper mapper)
            throws JsonProcessingException {
        AnthropicModelSettings settings = AnthropicModelSettings.resolve(config);
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            throw new NonRetriableModelException("Anthropic requires conversation messages", null);
        }

        ObjectNode thinking = null;
        if (settings.thinkingType() != null) {
            thinking = mapper.createObjectNode().put("type", settings.thinkingType());
            if (settings.budget() != null) thinking.put("budget_tokens", settings.budget());
        }
        boolean replayThinking = config.isSendThinking()
                || (thinking != null && !"disabled".equals(thinking.path("type").asText()));
        ArrayNode system = mapper.createArrayNode();
        List<Message> messages = new ArrayList<>();
        ArrayNode toolResults = null;
        for (var message : request.getMessages()) {
            if (message instanceof ToolMessageEntity tool) {
                if (toolResults == null) {
                    toolResults = mapper.createArrayNode();
                    messages.add(new Message("user", toolResults));
                }
                ObjectNode block = toolResults.addObject().put("type", "tool_result")
                        .put("tool_use_id", String.valueOf(tool.getId())).put("is_error", tool.isError());
                if (tool.getToolOutput() != null) block.put("content", tool.getToolOutput());
                continue;
            }
            toolResults = null;
            if (message instanceof SystemMessageEntity instruction) {
                addText(system, instruction.text());
            } else if (message instanceof UserMessageEntity user) {
                ArrayNode blocks = mapper.createArrayNode();
                for (var content : user.getContent()) {
                    if (content instanceof TextContent text) {
                        addText(blocks, text.getText());
                    } else if (content instanceof ImageContent image) {
                        addImage(blocks, image.getImage());
                    } else {
                        throw new NonRetriableModelException("Unsupported Anthropic user content", null);
                    }
                }
                if (!blocks.isEmpty()) messages.add(new Message("user", blocks));
            } else if (message instanceof AiMessageEntity assistant) {
                Object saved = assistant.getAttributes() == null ? null
                        : assistant.getAttributes().get(ResponseSerializer.NATIVE_CONTENT_ATTRIBUTE);
                if (replayThinking && saved != null && mapper.valueToTree(saved) instanceof ArrayNode blocks) {
                    messages.add(new Message("assistant", blocks));
                    continue;
                }
                ArrayNode blocks = mapper.createArrayNode();
                if (replayThinking) addLegacyThinking(blocks, assistant);
                addText(blocks, assistant.getText());
                if (assistant.getToolCalls() != null) {
                    for (ToolCallRequest call : assistant.getToolCalls()) {
                        blocks.addObject().put("type", "tool_use").put("id", call.id()).put("name", call.name())
                                .set("input", jsonObject(call.arguments(), mapper));
                    }
                }
                if (!blocks.isEmpty()) messages.add(new Message("assistant", blocks));
            }
        }
        if (messages.isEmpty()) {
            throw new NonRetriableModelException("Anthropic requires at least one conversation message", null);
        }
        ArrayNode tools = tools(request.getTools(), mapper);
        if (settings.cachePrefix()) {
            markCacheBoundary(system, mapper, settings.cacheTtl());
            markCacheBoundary(tools, mapper, settings.cacheTtl());
        }
        return builder().model(config.getModelName()).maxTokens(settings.maxTokens())
                .system(system.isEmpty() ? null : system).messages(messages).tools(tools).thinking(thinking)
                .outputConfig(settings.outputEffort() == null ? null
                        : mapper.createObjectNode().put("effort", settings.outputEffort()))
                .cacheControl(settings.cacheAutomatically() ? cacheControl(mapper, settings.cacheTtl()) : null)
                .build();
    }

    private static void addLegacyThinking(ArrayNode blocks, AiMessageEntity assistant) {
        var attributes = assistant.getAttributes();
        if (attributes == null) return;
        if (attributes.get("thinking_signature") instanceof String signature && assistant.getThinking() != null) {
            blocks.addObject().put("type", "thinking").put("thinking", assistant.getThinking()).put("signature", signature);
        }
        if (attributes.get("redacted_thinking") instanceof List<?> redacted) {
            for (Object value : redacted) {
                if (value instanceof String data) blocks.addObject().put("type", "redacted_thinking").put("data", data);
            }
        }
    }

    private static void markCacheBoundary(ArrayNode blocks, ObjectMapper mapper, String ttl) {
        if (blocks != null && !blocks.isEmpty()) {
            ((ObjectNode) blocks.get(blocks.size() - 1)).set("cache_control", cacheControl(mapper, ttl));
        }
    }

    private static ObjectNode cacheControl(ObjectMapper mapper, String ttl) {
        ObjectNode cache = mapper.createObjectNode().put("type", "ephemeral");
        if (ttl != null) cache.put("ttl", ttl);
        return cache;
    }


    private static ArrayNode tools(List<ToolDefinition<?>> definitions, ObjectMapper mapper) throws JsonProcessingException {
        if (definitions == null || definitions.isEmpty()) return null;
        ArrayNode tools = mapper.createArrayNode();
        for (ToolDefinition<?> definition : definitions) {
            ObjectNode tool = tools.addObject().put("name", definition.name());
            if (definition.description() != null) tool.put("description", definition.description());
            String schema = definition.parametersJsonSchema();
            tool.set("input_schema", schema == null || schema.isBlank()
                    ? mapper.createObjectNode().put("type", "object") : jsonObject(schema, mapper));
        }
        return tools;
    }

    private static JsonNode jsonObject(String value, ObjectMapper mapper) throws JsonProcessingException {
        if (value == null || value.isBlank()) return mapper.createObjectNode();
        JsonNode node = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(value);
        if (!node.isObject()) throw new NonRetriableModelException("Anthropic tool input and schema must be JSON objects", null);
        return node;
    }

    private static void addText(ArrayNode blocks, String text) {
        if (text != null && !text.isBlank()) blocks.addObject().put("type", "text").put("text", text);
    }

    private static void addImage(ArrayNode blocks, Image image) {
        ObjectNode source = blocks.addObject().put("type", "image").putObject("source");
        if (image.getBase64Data() != null && !image.getBase64Data().isBlank()) {
            source.put("type", "base64").put("media_type", image.mimeType()).put("data", image.getBase64Data());
        } else if (image.getUrl() != null) {
            source.put("type", "url").put("url", image.getUrl().toString());
        } else {
            throw new NonRetriableModelException("Anthropic image requires base64 data or a URL", null);
        }
    }
}
