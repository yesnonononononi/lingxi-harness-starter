package com.summit.harnesscore.conversation.message;

import com.summit.harnesscore.conversation.api.ToolCallRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.Getter;

import java.util.List;

@Builder
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
