package com.summit.harnesscore.conversation.api;

import com.summit.harnesscore.conversation.message.AiMessageEntity;
import com.summit.harnesscore.conversation.message.TokenUsageEntity;
import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class ChatResponseEntity {
    private AiMessageEntity aiMessageEntity;
    private TokenUsageEntity tokenUsage;
}
