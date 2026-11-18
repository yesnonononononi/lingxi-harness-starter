package com.summit.runtime.conversation;

import com.summit.core.agent.Execution;
import com.summit.core.compact.ContextAttachmentProvider;
import com.summit.core.compact.Tokenizer;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.ContextSectionKind;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.conversation.message.UserMessageEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Rebuilds a session's message list around a compaction summary.
 *
 * <p>Keeps the leading system prompt, protected application state, consolidated dynamic context,
 * and the latest user turn (capped when the trailing turn exceeds the tail budget), while
 * stripping stale thinking signatures whose prefix has changed.</p>
 */
public final class ContextRebuilder {

    private static final String CONTINUE_AFTER_COMPACTION_PROMPT = """
            The conversation history before this point has been compacted into the summary above.
            after that, lastest turn message. Continue the work from there: do not repeat steps that are already marked as completed.
            """;

    /**
     * Fraction of the context cap the trailing turn may occupy before {@link #retainableTail}
     * shrinks it down to the latest anchor steps.
     */
    private static final double TAIL_COMPACT_THRESHOLD = 0.20;

    private static final int FURTHER_COMPACT_MAX_GROUPS = 3;

    private final Tokenizer tokenizer;
    private final int maxTokens;
    private final ContextAttachmentProvider contextAttachmentProvider;

    public ContextRebuilder(Tokenizer tokenizer,
                            int maxTokens,
                            ContextAttachmentProvider contextAttachmentProvider) {
        this.tokenizer = Objects.requireNonNull(tokenizer, "tokenizer");
        this.maxTokens = maxTokens;
        this.contextAttachmentProvider = Objects.requireNonNull(
                contextAttachmentProvider, "contextAttachmentProvider");
    }

    /**
     * Produces the rebuilt message list for {@code execution} from {@code summary}.
     */
    public List<Message> rebuild(String summary, Execution execution, String fallbackBusinessPrompt) {
        List<Message> current = execution.getMessages();
        List<Message> rebuilt = new ArrayList<>();

        SystemMessageEntity leading = current.stream()
                .filter(SystemMessageEntity.class::isInstance)
                .map(SystemMessageEntity.class::cast)
                .findFirst()
                .orElseGet(() -> SystemMessageEntity.builder().text(fallbackBusinessPrompt).build());

        List<Message> attachment = new ArrayList<>();
        contextAttachmentProvider.attachment(execution.getId())
                .filter(value -> !value.isBlank())
                .ifPresent(value -> attachment.add(SystemMessageEntity.builder().text(String.format("""
                        The protected application state produced earlier is reproduced below. Keep following it:

                        %s
                        """, value)).build()));

        SystemMessageEntity summaryMessage = SystemMessageEntity.builder()
                .text(summary + "\n" + CONTINUE_AFTER_COMPACTION_PROMPT)
                .build();

        List<Message> retained = retainCompactionInputs(current);
        List<Message> dynamicContext = retained.stream()
                .filter(message -> message instanceof UserMessageEntity user && user.hasContextContract())
                .toList();
        List<Message> tail = stripOldThinking(retainableTail(retained));

        rebuilt.add(leading);
        rebuilt.addAll(attachment);
        rebuilt.add(summaryMessage);
        rebuilt.addAll(dynamicContext);
        rebuilt.addAll(tail);
        return rebuilt;
    }

    private List<Message> retainCompactionInputs(List<Message> current) {
        Map<ContextSectionKind, String> surviving = ContextDynamicAssembler.latestSections(current, current.size());
        surviving.values().removeIf(ContextDynamicAssembler.CLEARED_BODY::equals);

        List<Message> retained = new ArrayList<>(current.size());
        boolean hasLaterAssistantReply = false;
        boolean keptDynamicContext = false;
        // Walk backwards so a prior assistant reply cannot confirm a newly injected input.
        for (Message message : current.reversed()) {
            if (message instanceof AiMessageEntity) {
                hasLaterAssistantReply = true;
            }
            if (message instanceof UserMessageEntity compactionCandidate
                    && compactionCandidate.hasContextContract()) {
                // Dynamic context is appended incrementally by category; compaction folds the
                // latest surviving section per category into one consolidated <context> message.
                if (!keptDynamicContext) {
                    keptDynamicContext = true;
                    UserMessageEntity consolidated = consolidateDynamicContext(compactionCandidate, surviving);
                    if (consolidated != null) {
                        retained.add(consolidated);
                    }
                }
                continue;
            }
            Message candidate = message instanceof UserMessageEntity user
                    ? user.retainForCompaction(hasLaterAssistantReply) : message;
            if (candidate != null) {
                retained.add(candidate);
            }
        }
        return retained.reversed();
    }

    private static UserMessageEntity consolidateDynamicContext(UserMessageEntity newest,
                                                               Map<ContextSectionKind, String> surviving) {
        if (newest.contextSections().isEmpty()) {
            return newest;
        }
        if (surviving.isEmpty()) {
            return null;
        }
        if (newest.contextSections().size() == surviving.size()) {
            UserMessageEntity rebuilt = ContextDynamicAssembler.assembleSections(surviving);
            if (rebuilt != null && newest.text().equals(rebuilt.text())) {
                return newest;
            }
            return rebuilt;
        }
        return ContextDynamicAssembler.assembleSections(surviving);
    }

    private List<Message> retainableTail(List<Message> messages) {
        int lastUserMessageIndex = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessageEntity user
                    && !user.hasCompactionMarker()
                    && !user.hasContextContract()) {
                lastUserMessageIndex = i;
                break;
            }
        }
        if (lastUserMessageIndex == -1) {
            return List.of();
        }

        List<Message> result = new ArrayList<>();
        for (int i = lastUserMessageIndex; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (message instanceof UserMessageEntity user && user.hasContextContract()) {
                continue;
            }
            result.add(message);
        }

        double ratio = tokenizer.calcCurrentTokenRatio(result, List.of(), maxTokens);
        if (ratio >= TAIL_COMPACT_THRESHOLD) {
            return furtherCompact(result);
        }
        return result;
    }

    private List<Message> furtherCompact(List<Message> result) {
        List<Message> furtherResult = new ArrayList<>();
        UserMessageEntity anchorUser = !result.isEmpty()
                && result.getFirst() instanceof UserMessageEntity user
                && !user.hasCompactionMarker()
                ? user
                : null;
        if (anchorUser != null) {
            furtherResult.add(anchorUser);
        }

        int startBound = anchorUser != null ? 1 : 0;
        int group = 0;
        int cutIndex = -1;

        for (int i = result.size() - 1; i >= startBound; i--) {
            if (result.get(i) instanceof AiMessageEntity assistant) {
                cutIndex = i;
                boolean hasToolCalls = assistant.getToolCalls() != null && !assistant.getToolCalls().isEmpty();
                if (!hasToolCalls) {
                    // A plain-text reply is already a complete anchor; no need to pull earlier steps.
                    break;
                }
                group++;
                if (group >= FURTHER_COMPACT_MAX_GROUPS) {
                    break;
                }
            }
        }

        if (cutIndex != -1) {
            furtherResult.addAll(result.subList(cutIndex, result.size()));
            return furtherResult;
        }

        for (int i = startBound; i < result.size(); i++) {
            if (result.get(i) instanceof UserMessageEntity user) {
                furtherResult.add(user);
            }
        }
        return furtherResult;
    }

    /**
     * Removes thinking (and its provider signature attribute) from the retained trailing assistant
     * messages.
     *
     * <p>A summary rebuild replaces the history prefix, which invalidates any thinking signature the
     * trailing assistant turn still carries: replaying that signature under a changed prefix is what
     * a prefix-bound preserved-thinking model rejects (signature mismatch). Only this rebuild path
     * strips thinking; a plain snapshot restore keeps it, because only here does the prefix change.</p>
     */
    private static List<Message> stripOldThinking(List<Message> tail) {
        List<Message> stripped = new ArrayList<>(tail.size());
        for (Message message : tail) {
            if (message instanceof AiMessageEntity ai) {
                boolean thinkingFree = (ai.getThinking() == null || ai.getThinking().isBlank())
                        && (ai.getAttributes() == null || ai.getAttributes().isEmpty());
                stripped.add(thinkingFree ? ai : AiMessageEntity.builder()
                        .text(ai.getText())
                        .thinking(null)
                        .attributes(Map.of())
                        .toolCalls(ai.getToolCalls() == null ? List.of() : List.copyOf(ai.getToolCalls()))
                        .build());
            } else {
                stripped.add(message);
            }
        }
        return stripped;
    }
}
