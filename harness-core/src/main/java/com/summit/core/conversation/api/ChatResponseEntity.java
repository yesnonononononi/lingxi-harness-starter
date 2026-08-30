package com.summit.core.conversation.api;

import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.TokenUsageEntity;
import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class ChatResponseEntity {
    private AiMessageEntity aiMessageEntity;
    private TokenUsageEntity tokenUsage;
}
