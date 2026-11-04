package anthropic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@AllArgsConstructor
@NoArgsConstructor
public class AnthropicResponse {
    private String id;
    private String type;
    private String role;
    private java.util.List<Content> content;
    private String model;
    @JsonProperty("stop_reason")
    private String stopReason;
    private Usage usage;


    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = ToolUse.class, name = "tool_use"),
            @JsonSubTypes.Type(value = AssistantText.class, name = "text"),
            @JsonSubTypes.Type(value = Thinking.class, name = "thinking"),
            @JsonSubTypes.Type(value = RedactedThinking.class, name = "redacted_thinking")
    }
    )
    public interface Content {
    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AssistantText implements Content {
        private String text;
    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ToolUse implements Content {
        private String id;
        private String name;
        private ObjectNode input;
    }


    @AllArgsConstructor
    @NoArgsConstructor
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Thinking implements Content {
        private String thinking;
        private String signature;
    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RedactedThinking implements Content {
        private String data;
    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Usage {
        @JsonProperty("input_tokens")
        private int inputTokens;
        @JsonProperty("output_tokens")
        private int outputTokens;
        @JsonProperty("cache_creation_input_tokens")
        private int cacheCreationInputTokens;
        @JsonProperty("cache_read_input_tokens")
        private int cacheReadInputTokens;
    }
}
