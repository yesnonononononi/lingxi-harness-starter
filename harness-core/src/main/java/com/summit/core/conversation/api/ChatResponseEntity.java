package com.summit.core.conversation.api;

import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.TokenUsageEntity;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Builder
@Data
public class ChatResponseEntity {
    public  enum FinishReason{
        /**
         * The model call finished because the model decided the request was done.
         */
        STOP,

        /**
         * The call finished because the token length was reached.
         */
        LENGTH,

        /**
         * The call finished signalling a need for tool execution.
         */
        TOOL_EXECUTION,

        /**
         * The call finished signalling a need for content filtering.
         */
        CONTENT_FILTER,

        /**
         * The call finished for some other reason.
         */
        OTHER
    }
    @Data
    @Builder
    public static class Meta{
        private  String id;
        private  String modelName;
        private  FinishReason finishReason;
    }
    private AiMessageEntity aiMessageEntity;
    private Meta meta;
    private TokenUsageEntity tokenUsage;
}
