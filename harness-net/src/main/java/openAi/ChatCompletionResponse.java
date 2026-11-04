package openAi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatCompletionResponse {

    private String id;
    private String model;

    private final List<Choice> choices = new ArrayList<>();

    private Usage usage;
    private JsonNode error;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Choice {
        private Message message;
        private Delta delta;

        @JsonProperty("finish_reason")
        private String finishReason;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Message {
            private String content;

            @JsonProperty("tool_calls")
            private List<ToolCall> toolCalls;

            @JsonProperty("reasoning_content")
            private String reasoningContent;

            @Data
            @JsonIgnoreProperties(ignoreUnknown = true)
            public static class ToolCall {
                private String id;
                private Function function;

                @Data
                @JsonIgnoreProperties(ignoreUnknown = true)
                public static class Function {
                    private String name;
                    private String arguments;
                }
            }
        }


        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Delta {
            private String content;

            @JsonProperty("reasoning_content")
            private String reasoningContent;

            @JsonProperty("tool_calls")
            private List<ToolCallDelta> toolCalls;

            @Data
            @JsonIgnoreProperties(ignoreUnknown = true)
            public static class ToolCallDelta {
                private Integer index;
                private String id;
                private String type;
                private Function function;

                @Data
                @JsonIgnoreProperties(ignoreUnknown = true)
                public static class Function {
                    private String name;
                    private String arguments;
                }
            }
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Usage {
        @JsonProperty("prompt_tokens")
        private Integer promptTokens;

        @JsonProperty("completion_tokens")
        private Integer completionTokens;

        @JsonProperty("total_tokens")
        private Integer totalTokens;

        // DeepSeek
        @JsonProperty("prompt_cache_hit_tokens")
        private Integer cacheHitTokens;
        @JsonProperty("prompt_cache_miss_tokens")
        private Integer cacheMissTokens;

        // OpenAi
        @JsonProperty("prompt_tokens_details")
        private CacheDetails promptTokensDetails;

        @JsonProperty("completion_tokens_details")
        private CompletionDetails completionTokensDetails;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class CompletionDetails {
            @JsonProperty("reasoning_tokens")
            private int reasoningTokens;
        }

        @Data
        @AllArgsConstructor
        @NoArgsConstructor
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class CacheDetails {
            @JsonProperty("cached_tokens")
            private Integer cachedTokens;
            @JsonProperty("audio_tokens")
            private Integer audioTokens;
        }

        public static Usage empty() {
            return new Usage(0, 0, 0, 0, 0, new CacheDetails(0, 0), null);
        }


    }
}
