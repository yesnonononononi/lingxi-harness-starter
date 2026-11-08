package com.summit.core.agent;

import com.summit.core.conversation.message.Message;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.jackson.Jacksonized;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@Jacksonized
@NoArgsConstructor
@AllArgsConstructor
public class ContextSpec {
    private String systemPrompt;
    @Builder.Default
    private List<Message> context = new ArrayList<>();
    /** Optional memory bodies appended to the system prompt, in list order. */
    @Builder.Default
    private List<String> memory = List.of();

    public ContextSpec(String systemPrompt, List<String> memory) {
        this.systemPrompt = systemPrompt;
        this.memory = memory;
    }
}
