package openAi.image;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ImageRequest {
    private String model;
    private String prompt;
    @Builder.Default
    private int n = 1;
    private String size;
    private String quality;
    private String background;
    @JsonProperty("output_format")
    private String outputFormat;
}
