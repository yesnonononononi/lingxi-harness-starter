package com.summit.runtime.audit;

import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.conversation.message.ToolMessageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.conversation.message.content.Content;
import com.summit.core.conversation.message.content.TextContent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Partitions retained history without modifying the execution's messages. */
record AuditContext(boolean hasHistory, List<AiMessageEntity> assistantHistory, List<Message> userMessages,
                    List<Message> dynamicContextMessages, List<Message> systemMessages, List<Content> attachments,
                    List<ToolCallRequest> toolCalls, List<ToolResult> toolResults) {

    static AuditContext fromHistory(List<Message> history) {
        if (history == null) {
            return new AuditContext(false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }

        List<AiMessageEntity> assistantHistory = new ArrayList<>();
        List<Message> userMessages = new ArrayList<>();
        List<Message> dynamicContextMessages = new ArrayList<>();
        List<Message> systemMessages = new ArrayList<>();
        List<Content> attachments = new ArrayList<>();
        List<ToolCallRequest> toolCalls = new ArrayList<>();
        List<ToolResult> toolResults = new ArrayList<>();
        Map<String, ToolCallRequest> pendingCalls = new HashMap<>();

        for (Message message : history) {
            switch (message) {
                case AiMessageEntity assistant -> {
                    assistantHistory.add(assistant);
                    List<ToolCallRequest> calls = assistant.getToolCalls();
                    if (calls != null) {
                        for (ToolCallRequest call : calls) {
                            toolCalls.add(call);
                            if (call != null) pendingCalls.put(call.id(), call);
                        }
                    }
                }
                case ToolMessageEntity tool -> {
                    // Providers may reuse call IDs in later rounds.
                    ToolCallRequest call = tool.getId() == null ? null : pendingCalls.remove(tool.getId().toString());
                    toolResults.add(new ToolResult(tool, call));
                }
                case UserMessageEntity user -> {
                    // The per-round context block is framework-rendered background, not something
                    // the user said: reporting it as user tokens would blame the wrong party.
                    if (user.hasContextContract()) {
                        dynamicContextMessages.add(withoutAttachments(user, attachments));
                    } else {
                        userMessages.add(withoutAttachments(user, attachments));
                    }
                }
                case SystemMessageEntity system -> systemMessages.add(system);
                case null, default -> { }
            }
        }

        return new AuditContext(true, assistantHistory, userMessages, dynamicContextMessages, systemMessages,
                attachments, toolCalls, toolResults);
    }

    List<Message> assistantMessages() {
        return assistantHistory.stream().map(AuditContext::withoutArguments).toList();
    }

    private static Message withoutArguments(AiMessageEntity assistant) {
        List<ToolCallRequest> calls = assistant.getToolCalls();
        if (calls == null || calls.isEmpty()) return assistant;

        List<ToolCallRequest> auditCalls = calls.stream().map(call -> call.withArguments("")).toList();
        return new AiMessageEntity(assistant.getText(), assistant.getThinking(), auditCalls,
                assistant.getAttributes(), assistant.getImages());
    }

    private static UserMessageEntity withoutAttachments(UserMessageEntity user, List<Content> attachments) {
        List<Content> text = new ArrayList<>();
        if (user.getContent() != null) {
            for (Content content : user.getContent()) {
                if (content instanceof TextContent) text.add(content);
                else attachments.add(content);
            }
        }
        return new UserMessageEntity(text, user.getAttrs());
    }

    record ToolResult(ToolMessageEntity message, ToolCallRequest call) {}
}
