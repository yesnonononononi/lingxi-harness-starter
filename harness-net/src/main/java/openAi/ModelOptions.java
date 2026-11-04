package openAi;


import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Builder;
import lombok.Data;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@Data
@Builder
public class ModelOptions {
    private String model;
    @JsonProperty("reasoning_effort")
    private String reasoningEffort;
    @JsonProperty("max_tokens")
    private Integer maxTokens;
    @Builder.Default
    private boolean stream = false;
    private Map<String, String> thinking;
    @JsonProperty("stream_options")
    private Map<String, Boolean> streamOptions;
    @Builder.Default
    private List<ObjectNode> tools = new ArrayList<>();
    @Builder.Default
    private Map<String, String> metadata = new HashMap<>();
    @JsonProperty("messages")
    private JsonNode messages;
}
