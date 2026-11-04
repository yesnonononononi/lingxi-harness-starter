package openAi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.summit.core.conf.ModelConfig;
import com.summit.core.conversation.api.ChatRequestEntity;
import com.summit.core.tool.ToolDefinition;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;


@RequiredArgsConstructor
public class RequestBodyFactory {
    private final MessageSerializer messageSerializer;
    private final ObjectMapper JSON;
    public String build(ChatRequestEntity chatRequestEntity, ModelConfig modelConfig){
        try{

            ArrayNode jsonNodes = messageSerializer.messagesArrayOf(chatRequestEntity.getMessages(),modelConfig);


            ModelOptions options = ModelOptions.builder()
                    .model(modelConfig.getModelName())
                    .maxTokens(modelConfig.getMaxTokens())
                    .messages(jsonNodes)
                    .thinking(modelConfig.getModelName() != null
                            && modelConfig.getModelName().toLowerCase(Locale.ROOT).contains("deepseek")
                            ? Map.of("type", modelConfig.isReturnThinking() ? "enabled" : "disabled") : null)
                    .reasoningEffort(modelConfig.getReasoningEffort())
                    .streamOptions(modelConfig.isStream() ? Map.of("include_usage", true) : null)
                    .tools(toolsOf(chatRequestEntity.getTools()))
                    .stream(modelConfig.isStream())
                    .build();

            return JSON.writeValueAsString(options);
        }catch (JsonProcessingException jpe){
            throw new RuntimeException("Deformed messages", jpe);
        }

    }

    private List<ObjectNode> toolsOf(List<ToolDefinition<?>> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }

        List<ObjectNode> definitions = new ArrayList<>(tools.size());
        for (ToolDefinition<?> tool : tools) {
            ObjectNode definition = JSON.createObjectNode();
            definition.put("type", "function");
            ObjectNode function = definition.putObject("function");
            function.put("name", tool.name());
            if (tool.description() != null) {
                function.put("description", tool.description());
            }

            String schema = tool.parametersJsonSchema();
            if (schema != null && !schema.isBlank()) {
                JsonNode parameters;
                try {
                    parameters = JSON.reader()
                            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                            .readTree(schema);
                } catch (JsonProcessingException error) {
                    throw new IllegalArgumentException("Invalid parameter schema for tool '" + tool.name() + "'", error);
                }
                if (parameters == null || !parameters.isObject()) {
                    throw new IllegalArgumentException("Parameter schema for tool '" + tool.name() + "' must be a JSON object");
                }
                function.set("parameters", parameters);
            }
            definitions.add(definition);
        }
        return definitions;
    }
}
