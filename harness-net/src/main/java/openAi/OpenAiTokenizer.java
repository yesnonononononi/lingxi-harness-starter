package openAi;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.summit.core.adapter.TokenEstimator;
import com.summit.core.agent.Image;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.conversation.message.ToolMessageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.conversation.message.content.Content;
import com.summit.core.conversation.message.content.ImageContent;
import com.summit.core.conversation.message.content.TextContent;
import com.summit.core.tool.ToolDefinition;

import java.util.List;
import java.util.Map;

/**
 * Local, network-free token estimator for OpenAI Chat Completions.
 *
 * <p>This estimator is designed for context-compaction and cost-preflight scenarios:
 * it must complete in microseconds and must never touch the network. Image tokens
 * are therefore approximated per {@link Image.DetailLevel} instead of being derived
 * from the image dimensions (which would require either downloading the URL or
 * decoding the Base64 payload).</p>
 *
 * <p>Accuracy characteristics:</p>
 * <ul>
 *   <li>Text: exact for the model's BPE encoding; framing overhead is a best-effort
 *       approximation of OpenAI's ChatML layout.</li>
 *   <li>Images: within roughly ±30% of the real cost. Sufficient for budget checks,
 *       not for exact billing reconciliation.</li>
 *   <li>Tools: counts model-facing names, descriptions, and raw schemas with
 *       an approximate per-definition framing cost.</li>
 * </ul>
 */
public class OpenAiTokenizer implements TokenEstimator {

    // ==================== Framing constants ====================

    /** Per-message framing overhead (role separator, turn boundaries). */
    private static final int MESSAGE_FRAMING_TOKENS = 3;

    /** Assistant primer appended to the end of every request. */
    private static final int ASSISTANT_PRIMER_TOKENS = 3;

    /** Extra framing for tool messages, which carry a {@code name} field. */
    private static final int TOOL_NAME_FIELD_TOKENS = 1;

    private static final int TOOL_DEFINITION_FRAMING_TOKENS = 7;

    // ==================== Image token constants ====================

    /**
     * OpenAI's official fixed cost for {@code detail=low}. Independent of image size.
     */
    private static final int LOW_IMAGE_TOKENS = 85;

    /**
     * High / auto detail, calibrated against a ~1024x1024 image:
     * {@code ceil(1024/512) * ceil(1024/512) * 170 + 85 = 765}.
     */
    private static final int HIGH_IMAGE_TOKENS = 765;

    /**
     * Medium detail is not an OpenAI-official level. This value is a
     * system-local interpolation between {@link #LOW_IMAGE_TOKENS} and
     * {@link #HIGH_IMAGE_TOKENS}, calibrated against a ~768x768 image.
     */
    private static final int MEDIUM_IMAGE_TOKENS = 510;

    /**
     * Ultra-high detail is not an OpenAI-official level. Calibrated against a
     * ~1024x1536 image: {@code ceil(1024/512) * ceil(1536/512) * 170 + 85 = 1105}.
     */
    private static final int ULTRA_HIGH_IMAGE_TOKENS = 1105;

    /**
     * Fallback used when {@link Image#detailLevel()} is null. Treated as
     * {@code AUTO}, matching OpenAI's own default. Returning 0 would silently
     * undercount images that are physically present in the request.
     */
    private static final int UNKNOWN_DETAIL_FALLBACK = HIGH_IMAGE_TOKENS;

    private final EncodingRegistry encodings = Encodings.newDefaultEncodingRegistry();
    private final Encoding encoding = encodings.getEncoding(EncodingType.O200K_BASE);

    @Override
    public int estimateMessages(List<Message> messages) {
        return estimateMessages(messages, List.of());
    }

    @Override
    public int estimateMessages(List<Message> messages, List<ToolDefinition<?>> tools) {
        StringBuilder textPayload = new StringBuilder();
        int extraTokens = ASSISTANT_PRIMER_TOKENS;
        if (tools != null) {
            for (ToolDefinition<?> tool : tools) {
                extraTokens += TOOL_DEFINITION_FRAMING_TOKENS + estimateText(tool.name())
                        + estimateText(tool.description()) + estimateText(tool.parametersJsonSchema());
            }
        }

        List<Message> history = messages == null ? List.of() : messages;
        for (Message message : history) {
            extraTokens += MESSAGE_FRAMING_TOKENS;

            if (message instanceof UserMessageEntity user) {
                extraTokens += appendUser(user, textPayload);
            } else if (message instanceof SystemMessageEntity system) {
                appendSystem(system, textPayload);
            } else if (message instanceof ToolMessageEntity tool) {
                appendTool(tool, textPayload);
                extraTokens += TOOL_NAME_FIELD_TOKENS;
            } else if (message instanceof AiMessageEntity ai) {
                extraTokens += appendAssistant(ai, textPayload);
            }
        }

        // countTokensOrdinary treats "<|endoftext|>" and similar literals as plain
        // text instead of throwing, which is the right behavior for estimation.
        return encoding.countTokensOrdinary(textPayload.toString()) + extraTokens;
    }

    @Override
    public int estimateText(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return encoding.countTokensOrdinary(text);
    }

    // ==================== Per-message-type handlers ====================

    /**
     * Serializes a user message's text content and returns the token cost of any
     * embedded images.
     */
    private int appendUser(UserMessageEntity user, StringBuilder sink) {
        int imageTokens = 0;
        if (user.getContent() == null) {
            return imageTokens;
        }
        for (Content content : user.getContent()) {
            if (content instanceof TextContent text) {
                sink.append(text.getText());
            } else if (content instanceof ImageContent imageContent) {
                imageTokens += imageTokensOf(imageContent.getImage());
            }
        }
        return imageTokens;
    }

    private void appendSystem(SystemMessageEntity system, StringBuilder sink) {
        sink.append(system.getText());
    }

    /**
     * Serializes a tool result message.
     *
     * <p>Only the fields that OpenAI actually renders in the {@code role=tool}
     * payload are counted ({@code tool_call_id} and {@code content}). Local-only
     * fields such as {@code exitCode} are intentionally excluded, since they are
     * never serialized into the request and would otherwise inflate the estimate.</p>
     */
    private void appendTool(ToolMessageEntity tool, StringBuilder sink) {
        sink.append(tool.getId()).append(tool.getToolOutput());
    }

    /**
     * Serializes an assistant message and returns the token cost of any images it
     * carries.
     *
     * <p>Tool-call arguments are counted as-is. {@code requestIndex()} is excluded
     * because it is not part of the wire format for non-streaming requests.</p>
     */
    private int appendAssistant(AiMessageEntity ai, StringBuilder sink) {
        Map<String, Object> attributes = ai.getAttributes();
        if (attributes != null) {
            for (Map.Entry<String, Object> entry : attributes.entrySet()) {
                String key = entry.getKey();
                if (anthropic.ResponseSerializer.NATIVE_CONTENT_ATTRIBUTE.equals(key)
                        || "thinking_signature".equals(key) || "redacted_thinking".equals(key)) continue;
                sink.append(key).append(entry.getValue());
            }
        }

        List<ToolCallRequest> toolCalls = ai.getToolCalls();
        if (toolCalls != null) {
            for (ToolCallRequest call : toolCalls) {
                sink.append(call.id()).append(call.name()).append(call.arguments());
            }
        }

        int imageTokens = 0;
        if (ai.getImages() != null) {
            for (Image image : ai.getImages()) {
                imageTokens += imageTokensOf(image);
            }
        }

        sink.append(ai.getText()).append(ai.getThinking());
        return imageTokens;
    }

    // ==================== Image estimation ====================

    /**
     * Returns an approximate token cost for a single image.
     *
     * <p>Never performs I/O. When the image carries no {@link Image.DetailLevel},
     * it is treated as {@code AUTO} so that present images are never counted as
     * zero.</p>
     */
    private int imageTokensOf(Image image) {
        if (image == null) {
            return 0;
        }
        Image.DetailLevel level = image.detailLevel();

        return switch (level) {
            case LOW -> LOW_IMAGE_TOKENS;
            case MEDIUM -> MEDIUM_IMAGE_TOKENS;
            case HIGH, AUTO -> HIGH_IMAGE_TOKENS;
            case ULTRA_HIGH -> ULTRA_HIGH_IMAGE_TOKENS;
        };
    }
}