package com.summit.core.conversation.message;

import com.summit.core.conversation.api.ToolCallRequest;
import lombok.*;

import java.util.List;

@Builder
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Getter
public class AiMessageEntity implements Message{
    private String text;
    private String thinking;
    private List<ToolCallRequest> toolCalls;

    @Override
    public String text() {
        return this.text;
    }


}
