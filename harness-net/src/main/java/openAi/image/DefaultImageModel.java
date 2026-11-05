package openAi.image;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.agent.Image;
import com.summit.core.conf.ModelConfig;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.TokenUsageEntity;
import com.summit.core.conversation.token.ImageTokenUsage;
import com.summit.core.exception.NonRetriableModelException;
import com.summit.core.exception.RetriableModelException;
import com.summit.core.model.image.ImageGenerationOptions;
import com.summit.core.model.image.ImageModel;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@RequiredArgsConstructor
public class DefaultImageModel implements ImageModel {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    /**
     * Longest server-provided error text echoed into an exception message.
     *
     * <p>The provider's {@code error.message} is what tells an operator whether a request was
     * rejected for its size, its prompt or its account; dropping it turned every failure into a
     * bare status code. It is truncated all the same, because a misconfigured base URL can answer
     * with an HTML page whose text has no place in a log line or in a retry decision.</p>
     */
    private static final int MAX_ERROR_DETAIL_LENGTH = 300;

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final ModelConfig modelConfig;

    @Override
    public ChatResponseEntity generate(String prompt) {
        return generate(prompt, ImageGenerationOptions.EMPTY);
    }

    @Override
    public ChatResponseEntity generate(String prompt, int n) {
        return generate(prompt, ImageGenerationOptions.builder().count(n).build());
    }

    @Override
    public ChatResponseEntity generate(String prompt, ImageGenerationOptions options) {
        ImageRequest request = requestOf(prompt, options, false);
        try {
            return send("generations", "application/json",
                    HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request)));
        } catch (JsonProcessingException error) {
            throw new NonRetriableModelException("Cannot serialize openAi.image request", error);
        }
    }

    @Override
    public ChatResponseEntity edit(Image image, String prompt) {
        return edit(image, prompt, ImageGenerationOptions.EMPTY);
    }

    @Override
    public ChatResponseEntity edit(Image image, String prompt, ImageGenerationOptions options) {
        return edit(image, null, prompt, options);
    }

    @Override
    public ChatResponseEntity edit(Image image, Image mask, String prompt) {
        return edit(image, Objects.requireNonNull(mask, "mask"), prompt, ImageGenerationOptions.EMPTY);
    }

    @Override
    public ChatResponseEntity edit(Image image, Image mask, String prompt, ImageGenerationOptions options) {
        return editWithReferences(Collections.singletonList(image), mask, prompt, options);
    }

    @Override
    public ChatResponseEntity editWithReferences(List<Image> images, Image mask, String prompt, ImageGenerationOptions options) {
        if (images == null || images.isEmpty()) {
            throw new NonRetriableModelException("Image editing requires at least one reference image", null);
        }
        ImageRequest request = requestOf(prompt, options, true);
        String boundary = UUID.randomUUID().toString();
        List<HttpRequest.BodyPublisher> parts = new ArrayList<>();
        addField(parts, boundary, "model", request.getModel());
        addField(parts, boundary, "prompt", request.getPrompt());
        addField(parts, boundary, "size", request.getSize());
        addField(parts, boundary, "quality", request.getQuality());
        addField(parts, boundary, "background", request.getBackground());
        addField(parts, boundary, "output_format", request.getOutputFormat());
        String field = images.size() == 1 ? "image" : "image[]";
        for (Image image : images) {
            addImagePart(parts, boundary, field, image);
        }
        if (mask != null) addImagePart(parts, boundary, "mask", mask);
        parts.add(HttpRequest.BodyPublishers.ofString("--" + boundary + "--\r\n"));
        return send("edits", "multipart/form-data; boundary=" + boundary,
                HttpRequest.BodyPublishers.concat(parts.toArray(HttpRequest.BodyPublisher[]::new)));
    }

    private ImageRequest requestOf(String prompt, ImageGenerationOptions options, boolean edit) {
        if (nonBlank(prompt) == null) throw new NonRetriableModelException("Image prompt must be non-blank", null);
        ModelConfig.ImageOptions imageOptions = modelConfig.getImageOptions();
        ImageGenerationOptions defaults = imageOptions == null ? ImageGenerationOptions.EMPTY
                : new ImageGenerationOptions(nonBlank(imageOptions.size()), nonBlank(imageOptions.quality()),
                        edit ? Integer.valueOf(1) : imageOptions.count());
        ImageGenerationOptions effective = Objects.requireNonNullElse(options, ImageGenerationOptions.EMPTY).withDefaults(defaults);
        if (edit && effective.count() != 1) {
            throw new UnsupportedOperationException("Image editing supports exactly one output openAi.image");
        }
        return ImageRequest.builder()
                .model(modelConfig.getModelName())
                .prompt(prompt)
                .n(effective.count())
                .size(effective.size())
                .quality(effective.quality())
                .background(effective.background())
                .outputFormat(effective.outputFormat())
                .build();
    }

    private ChatResponseEntity send(String operation, String contentType, HttpRequest.BodyPublisher body) {
        HttpRequest request = buildRequest(operation, contentType, body);
        try {
            return toResponse(mapper.readValue(doSend(operation, request), ImageResponse.class));
        } catch (JsonProcessingException error) {
            throw new NonRetriableModelException("Invalid response for image " + operation, null);
        } catch (IOException error) {
            throw new RetriableModelException("Image " + operation + " transport failed", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new NonRetriableModelException("Image " + operation + " interrupted", error);
        }
    }

    private HttpRequest buildRequest(String operation, String contentType, HttpRequest.BodyPublisher body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(resolveEndpoint(operation))
                .timeout(Objects.requireNonNullElse(modelConfig.getTimeout(), DEFAULT_TIMEOUT));
        if (modelConfig.getApiKey() != null) builder.header("Authorization", "Bearer " + modelConfig.getApiKey());
        if (modelConfig.getImageOptions() != null) modelConfig.getImageOptions().customHeaders().forEach(builder::setHeader);
        return builder.setHeader("Content-Type", contentType).POST(body).build();
    }

    private String doSend(String operation, HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status >= 200 && status < 300) return response.body();

        String message = "Image " + operation + " failed with HTTP " + status + errorDetailOf(response.body());
        if (status == 408 || status == 429 || status >= 500) {
            throw new RetriableModelException(message, null);
        }
        throw new NonRetriableModelException(message, null);
    }

    /**
     * Renders the provider's own error text as an exception-message suffix.
     *
     * @param body raw response body of a failed request, may be null
     * @return {@code ": <error.message>"} when the body carries one, otherwise an empty string so
     *         that an unparseable or unexpected body still produces the status-only message
     */
    private String errorDetailOf(String body) {
        String detail = errorMessageOf(body);
        return detail == null ? "" : ": " + detail;
    }

    private String errorMessageOf(String body) {
        if (nonBlank(body) == null) return null;
        try {
            JsonNode root = mapper.readTree(body);
            if (root == null) return null;
            String message = nonBlank(root.path("error").path("message").asText(null));
            if (message == null) return null;
            String stripped = message.strip();
            return stripped.length() <= MAX_ERROR_DETAIL_LENGTH
                    ? stripped : stripped.substring(0, MAX_ERROR_DETAIL_LENGTH) + "...";
        } catch (JsonProcessingException unparseable) {
            // A gateway page or a truncated body: the status code is all that is left to report.
            return null;
        }
    }

    private ChatResponseEntity toResponse(ImageResponse response) {
        List<Image> images = response == null || response.getData() == null ? List.of() : response.getData().stream()
                .filter(Objects::nonNull)
                .filter(item -> nonBlank(item.getB64Json()) != null || item.getUrl() != null)
                .map(item -> {
                    String base64 = nonBlank(item.getB64Json());
                    return Image.builder().base64Data(base64).url(base64 == null ? item.getUrl() : null)
                            .mimeType(response.getOutputFormat() == null ? null : "image/" + response.getOutputFormat())
                            .revisedPrompt(item.getRevisedPrompt()).build();
                }).toList();
        if (images.isEmpty()) throw new NonRetriableModelException("Image response contained no images", null);

        return ChatResponseEntity.builder()
                .aiMessageEntity(AiMessageEntity.builder().images(images).toolCalls(List.of()).build())
                .meta(ChatResponseEntity.Meta.builder().images(images)
                        .finishReason(ChatResponseEntity.FinishReason.OTHER).build())
                .tokenUsage(tokenUsageOf(response.getUsage()))
                .build();
    }

    private static TokenUsageEntity tokenUsageOf(ImageResponse.Usage usage) {
        if (usage == null) return TokenUsageEntity.empty();
        var input = usage.getInputTokenDetails();
        var output = usage.getOutputTokenDetails();
        return ImageTokenUsage.builder()
                .inputTokens(new TokenUsageEntity.InputTokens(usage.getInputTokens(), 0,
                        input == null ? 0 : input.getImageTokens(), input == null ? null : input.getTextTokens()))
                .outputTokens(new TokenUsageEntity.OutputTokens(usage.getOutputTokens(), 0,
                        output == null ? 0 : output.getImageTokens(), output == null ? null : output.getTextTokens()))
                .totalTokens(usage.getTotalTokens())
                .build();
    }

    private URI resolveEndpoint(String operation) {
        URI base;
        try {
            base = URI.create(Objects.requireNonNullElse(modelConfig.getBaseUrl(), "https://api.openai.com/v1"));
        } catch (IllegalArgumentException error) {
            throw new NonRetriableModelException("Invalid openAi.image model base URL", null);
        }
        if (!("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))
                || base.getHost() == null || base.getRawUserInfo() != null || base.getRawFragment() != null
                || base.getRawPath().startsWith("//")) {
            throw new NonRetriableModelException("Image model base URL must be an HTTP(S) endpoint without credentials or a fragment", null);
        }

        String path = base.getRawPath().replaceFirst("/+$", "");
        if (path.endsWith("/images") || path.endsWith("/images/generations") || path.endsWith("/images/edits")) {
            path = path.substring(0, path.lastIndexOf("/images"));
        }
        String query = base.getRawQuery();
        return base.resolve(path + "/images/" + operation + (query == null ? "" : "?" + query));
    }

    private static void addField(List<HttpRequest.BodyPublisher> parts, String boundary, String name, String value) {
        if (value == null) return;
        parts.add(HttpRequest.BodyPublishers.ofString("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n"));
    }

    private static void addImagePart(List<HttpRequest.BodyPublisher> parts, String boundary, String name, Image image) {
        if (image == null || nonBlank(image.getBase64Data()) == null) {
            throw new NonRetriableModelException("Image editing requires base64 data for " + name, null);
        }
        String mimeType = image.mimeType().toLowerCase(Locale.ROOT);
        String extension = switch (mimeType) {
            case "image/png" -> ".png";
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/webp" -> ".webp";
            default -> throw new NonRetriableModelException("Unsupported content type for " + name, null);
        };
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(image.getBase64Data());
        } catch (IllegalArgumentException error) {
            throw new NonRetriableModelException("Invalid base64 data for " + name, error);
        }
        parts.add(HttpRequest.BodyPublishers.ofString("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + name + extension + "\"\r\n"
                + "Content-Type: " + mimeType + "\r\n\r\n"));
        parts.add(HttpRequest.BodyPublishers.ofByteArray(bytes));
        parts.add(HttpRequest.BodyPublishers.ofString("\r\n"));
    }

    private static String nonBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
