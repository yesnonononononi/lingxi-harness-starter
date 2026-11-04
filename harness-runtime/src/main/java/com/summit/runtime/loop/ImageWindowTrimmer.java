package com.summit.runtime.loop;

import com.summit.core.agent.Execution;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.conversation.message.content.Content;
import com.summit.core.conversation.message.content.ImageContent;

import java.util.ArrayList;
import java.util.List;

/** Bounds inline image payload independently of the text token budget. */
final class ImageWindowTrimmer {
    private static final int MAX_IMAGES = 15;
    private static final long MAX_INLINE_BYTES = 24L * 1024 * 1024;
    private static final int TARGET_IMAGES = 7;
    private static final long TARGET_INLINE_BYTES = 12L * 1024 * 1024;

    private ImageWindowTrimmer() {
    }

    static void trim(Execution execution) {
        List<Message> messages = execution.getMessages();
        if (messages == null || !exceedsWindow(messages)) return;

        int remainingImages = TARGET_IMAGES;
        long remainingBytes = TARGET_INLINE_BYTES;
        List<Message> retained = new ArrayList<>(messages.size());
        for (Message message : messages.reversed()) {
            if (!(message instanceof UserMessageEntity user) || user.getContent() == null) {
                retained.add(message);
                continue;
            }

            List<Content> contents = user.getContent();
            List<Content> kept = new ArrayList<>(contents.size());
            for (Content content : contents.reversed()) {
                if (content instanceof ImageContent image) {
                    int inlineBytes = inlineBytes(image);
                    // Preserve a fresh image that fits the hard budget even if it exceeds the target.
                    long allowedBytes = remainingImages == TARGET_IMAGES ? MAX_INLINE_BYTES : remainingBytes;
                    if (remainingImages == 0 || inlineBytes > allowedBytes) {
                        // Keep a contiguous window of the newest images.
                        remainingImages = 0;
                        continue;
                    }
                    remainingImages--;
                    remainingBytes -= inlineBytes;
                }
                kept.add(content);
            }

            if (kept.size() == contents.size()) {
                retained.add(user);
            } else if (!kept.isEmpty()) {
                retained.add(UserMessageEntity.builder().content(new ArrayList<>(kept.reversed()))
                        .attrs(user.getAttrs()).build());
            }
        }
        execution.setMessages(new ArrayList<>(retained.reversed()));
    }

    private static boolean exceedsWindow(List<Message> messages) {
        int imageCount = 0;
        long bytes = 0;
        for (Message message : messages.reversed()) {
            if (!(message instanceof UserMessageEntity user) || user.getContent() == null) continue;
            for (Content content : user.getContent()) {
                if (content instanceof ImageContent image) {
                    imageCount++;
                    bytes += inlineBytes(image);
                    if (imageCount > MAX_IMAGES || bytes > MAX_INLINE_BYTES) return true;
                }
            }
        }
        return false;
    }

    private static int inlineBytes(ImageContent image) {
        String base64 = image.getImage().getBase64Data();
        return base64 == null ? 0 : base64.length();
    }
}
