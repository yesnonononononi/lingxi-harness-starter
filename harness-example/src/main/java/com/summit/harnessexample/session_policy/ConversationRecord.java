package com.summit.harnessexample.session_policy;

import com.summit.core.conversation.message.Message;

import java.io.Serializable;
import java.util.List;

/** Business-owned persisted conversation. It is deliberately outside the harness API. */
public record ConversationRecord(String conversationId, String name, List<Message> messages)
        implements Serializable {
    public ConversationRecord {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public ConversationRecord withName(String newName) {
        return new ConversationRecord(conversationId, newName, messages);
    }
}
