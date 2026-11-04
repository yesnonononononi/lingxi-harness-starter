package openAi.image;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.net.URI;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ImageResponse {

    @JsonProperty("output_format")
    private String outputFormat;
    private List<Data> data;

    @lombok.Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Data {
        @JsonProperty("b64_json")
        private String b64Json;
        private URI url;
        @JsonProperty("revised_prompt")
        private String revisedPrompt;
    }

    private Usage usage;

    @lombok.Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Usage {
        @JsonProperty("total_tokens")
        private int totalTokens;
        @JsonProperty("input_tokens")
        private int inputTokens;
        @JsonProperty("output_tokens")
        private int outputTokens;
        @JsonProperty("input_tokens_details")
        private TokenDetails inputTokenDetails;
        @JsonProperty("output_tokens_details")
        private TokenDetails outputTokenDetails;

        @lombok.Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class TokenDetails {
            @JsonProperty("text_tokens")
            private Integer textTokens;
            @JsonProperty("image_tokens")
            private int imageTokens;
        }
    }
}
