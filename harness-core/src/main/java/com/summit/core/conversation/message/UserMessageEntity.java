package com.summit.core.conversation.message;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.summit.core.agent.Image;
import com.summit.core.conversation.message.content.Content;
import com.summit.core.conversation.message.content.ImageContent;
import com.summit.core.conversation.message.content.TextContent;
import lombok.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@JsonIgnoreProperties(value = "type", allowGetters = true)
public class UserMessageEntity implements Message{
    public static final String COMPACT = "COMPACT";
    public static final String CONTEXT_CONTRACT = "CONTEXT_CONTRACT";

    private List<Content> content;
    @Builder.Default
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, Boolean> attrs = Map.of();
    private final MessageType type = MessageType.USER;

    public UserMessageEntity(List<Content> content) {
        this();
        this.content = content;
    }

    public UserMessageEntity markContextContract(){
        Map<String, Boolean> updated = attrs == null ? new HashMap<>() : new HashMap<>(attrs);
        updated.putIfAbsent(CONTEXT_CONTRACT,false);
        attrs = updated;
        return this;
    }

    public UserMessageEntity markPendingCompaction() {
        Map<String, Boolean> updated = attrs == null ? new HashMap<>() : new HashMap<>(attrs);
        updated.putIfAbsent(COMPACT, false);
        attrs = updated;
        return this;
    }

    public boolean hasCompactionMarker() {
        return attrs != null && attrs.containsKey(COMPACT);
    }
    public boolean hasContextContract() {
        return attrs != null && attrs.containsKey(CONTEXT_CONTRACT);
    }

    /** A later assistant reply permits removal on a subsequent compaction. */
    public UserMessageEntity retainForCompaction(boolean hasLaterAssistantReply) {
        if (!hasCompactionMarker()) return this;
        if (Boolean.TRUE.equals(attrs.get(COMPACT))) return null;
        if (!hasLaterAssistantReply) return this;

        Map<String, Boolean> updated = new HashMap<>(attrs);
        updated.put(COMPACT, true);
        return new UserMessageEntity(content, updated);
    }

    @Override
    public String text() {
        for (Content c : content) {
            if(c instanceof TextContent t){
                return t.getText();
            }
        }
        return "";
    }
    @Override
    public MessageType type() {
        return this.type;
    }

    public static UserMessageEntity from(@NonNull String text, @NonNull List<Image> images){
        List<Content> contents = new ArrayList<>();
        contents.add(TextContent.from(text));
        contents.addAll(images.stream().map(ImageContent::from).toList());
        return UserMessageEntity.builder()
                .content(contents)
                .build();
    }

    public static UserMessageEntity from(@NonNull String text,@NonNull Image image){
        return UserMessageEntity.builder()
                .content(new ArrayList<>(List.of(TextContent.from(text), ImageContent.from(image))))
                .build();
    }
    public static UserMessageEntity from(@NonNull Image image){
        return UserMessageEntity.builder()
                .content(new ArrayList<>(List.of(ImageContent.from(image))))
                .build();
    }

    public static  UserMessageEntity from(@NonNull String text){
        return UserMessageEntity.builder()
                .content(new ArrayList<>(List.of(TextContent.from(text))))
                .build();
    }
}
