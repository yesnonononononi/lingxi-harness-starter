package openAi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.summit.core.agent.Image;
import com.summit.core.conf.ModelConfig;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.message.*;
import com.summit.core.conversation.message.content.Content;
import com.summit.core.conversation.message.content.ImageContent;
import com.summit.core.conversation.message.content.TextContent;
import lombok.RequiredArgsConstructor;

import java.net.MalformedURLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@RequiredArgsConstructor
public class MessageSerializer {
    private final ObjectMapper JSON;


    public ArrayNode messagesArrayOf(List<Message> messages, ModelConfig modelConfig) throws JsonProcessingException {
        ArrayNode array = JSON.createArrayNode();
        for (Object m : messages) {
            if (m instanceof UserMessageEntity userMessageEntity) array.add(JSON.readTree(userMessageOf(userMessageEntity)));
            else if (m instanceof SystemMessageEntity s) array.add(JSON.readTree(systemContentOf(s)));
            else if (m instanceof AiMessageEntity a)
                array.add(JSON.readTree(aiMessageOf(a, modelConfig.isSendThinking())));
            else if (m instanceof ToolMessageEntity t) array.add(JSON.readTree(toolOf(t)));
        }

        return array;
    }

    public String userMessageOf(UserMessageEntity userMessageEntity) {
        ObjectNode objectNode = JSON.createObjectNode();
        objectNode.put("role", "user");
        if (userMessageEntity.getContent().size() == 1 && userMessageEntity.getContent().getFirst() instanceof TextContent text) {
            return objectNode.put("content", text.getText()).toString();
        }
        ArrayNode contentNode = JSON.createArrayNode();
        objectNode.set("content", contentNode);

        for (Content content : userMessageEntity.getContent()) {
            JsonNode jsonNode = null;
            if (content instanceof TextContent textContent) {
                jsonNode = this.textContentOf(textContent);
            }
            if (content instanceof ImageContent imageContent) {
                jsonNode = this.imageContentOf(imageContent);
            }
            if (jsonNode != null) contentNode.add(jsonNode);
        }
        return objectNode.toString();
    }

    public JsonNode imageContentOf(ImageContent imageContent) {
        Objects.requireNonNull(imageContent);
        Image image = imageContent.getImage();

        Objects.requireNonNull(image);
        ObjectNode objectNode = JSON.createObjectNode();

        objectNode.put("type", "image_url");

        ObjectNode imageUrlNode = JSON.createObjectNode();
        try {
            String data = image.getUrl() != null ?
                    image.getUrl().toURL().toString() :
                    "data:" + image.mimeType() + ";base64," + image.getBase64Data();

            imageUrlNode.put("url", data);
            imageUrlNode.put("detail", image.detailLevel().name().toLowerCase(Locale.ROOT));
            objectNode.set("image_url",imageUrlNode);
            return objectNode;
        } catch (MalformedURLException malformedURLException) {
            throw new RuntimeException(malformedURLException);
        }
    }


    public JsonNode textContentOf(TextContent textContent) {
        ObjectNode objectNode = JSON.createObjectNode();
        objectNode.put("type", "text");
        objectNode.put("text", textContent.getText());
        return objectNode;
    }

    public String systemContentOf(SystemMessageEntity systemMessageEntity) {
        ObjectNode objectNode = JSON.createObjectNode();
        objectNode.put("role", "system");
        objectNode.put("content", systemMessageEntity.getText());
        return objectNode.toString();
    }

    public String aiMessageOf(AiMessageEntity aiMessageEntity, boolean isSendThinking) {
        ObjectNode objectNode = JSON.createObjectNode();
        objectNode.put("role", "assistant");
        String text = aiMessageEntity.getText();
        if ((text == null || text.isBlank()) && aiMessageEntity.getImages() != null && !aiMessageEntity.getImages().isEmpty()) {
            text = "[Assistant generated " + aiMessageEntity.getImages().size() + " image(s)]";
        }
        objectNode.put("content", text);
        if (isSendThinking && aiMessageEntity.getThinking() != null) objectNode.put("reasoning_content", aiMessageEntity.getThinking());
        ArrayNode toolCalls = JSON.createArrayNode();
        List<ToolCallRequest> calls = Objects.requireNonNullElse(aiMessageEntity.getToolCalls(), List.of());
        if (!calls.isEmpty()) objectNode.set("tool_calls", toolCalls);
        for (ToolCallRequest toolCall : calls) {
            ObjectNode toolCallItem = JSON.createObjectNode();
            toolCallItem.put("id", toolCall.id());
            toolCallItem.put("type", "function");
            ObjectNode function = JSON.createObjectNode();
            toolCallItem.set("function", function);
            function.put("name", toolCall.name());
            function.put("arguments", toolCall.arguments());
            toolCalls.add(toolCallItem);
        }
        Map<String, Object> attributes = aiMessageEntity.getAttributes();
        if (attributes != null && !attributes.isEmpty()) {
            for (Map.Entry<String, Object> entry : attributes.entrySet()) {
                if (anthropic.ResponseSerializer.NATIVE_CONTENT_ATTRIBUTE.equals(entry.getKey())
                        || "thinking_signature".equals(entry.getKey()) || "redacted_thinking".equals(entry.getKey())) continue;
                objectNode.put(entry.getKey(), entry.getValue() != null ? entry.getValue().toString() : null);
            }
        }
        return objectNode.toString();
    }

    public String toolOf(ToolMessageEntity toolMessageEntity) {
        ObjectNode objectNode = JSON.createObjectNode();
        objectNode.put("role", "tool");
        objectNode.put("tool_call_id", String.valueOf(toolMessageEntity.getId()));
        objectNode.put("content", toolMessageEntity.getToolOutput());
        return objectNode.toString();
    }


}
