package com.summit.runtime.audit;

import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.ContextSectionKind;
import com.summit.core.conversation.message.ContextSectionMark;
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
                    List<Message> dynamicContextMessages, List<DynamicSectionSpan> dynamicContextSections,
                    List<Message> systemMessages, List<Content> attachments,
                    List<ToolCallRequest> toolCalls, List<ToolResult> toolResults) {

    static AuditContext fromHistory(List<Message> history) {
        if (history == null) {
            return new AuditContext(false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of());
        }

        List<AiMessageEntity> assistantHistory = new ArrayList<>();
        List<Message> userMessages = new ArrayList<>();
        List<Message> dynamicContextMessages = new ArrayList<>();
        List<DynamicSectionSpan> dynamicContextSections = new ArrayList<>();
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
                    if (user.hasContextContract()) {
                        dynamicContextMessages.add(withoutAttachments(user, attachments));
                        dynamicContextSections.addAll(sectionsOf(user));
                    } else {
                        userMessages.add(withoutAttachments(user, attachments));
                    }
                }
                case SystemMessageEntity system -> systemMessages.add(system);
                case null, default -> { }
            }
        }

        return new AuditContext(true, assistantHistory, userMessages, dynamicContextMessages,
                dynamicContextSections, systemMessages, attachments, toolCalls, toolResults);
    }

    /** Slices one context message into the spans it declared, dropping spans the text no longer covers. */
    private static List<DynamicSectionSpan> sectionsOf(UserMessageEntity user) {
        String text = user.text();
        List<DynamicSectionSpan> spans = new ArrayList<>();
        for (ContextSectionMark mark : user.contextSections()) {
            int end = mark.start() + mark.length();
            if (mark.start() < 0 || end > text.length()) continue;
            spans.add(new DynamicSectionSpan(mark.kind(), text.substring(mark.start(), end)));
        }
        return spans;
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
        return UserMessageEntity.builder()
                .content(text)
                .attrs(user.getAttrs())
                .contextSections(user.contextSections())
                .build();
    }

    /** One section body, already cut out of the context message it was rendered into. */
    record DynamicSectionSpan(ContextSectionKind kind, String body) {
    }

    record ToolResult(ToolMessageEntity message, ToolCallRequest call) {
    }
}
