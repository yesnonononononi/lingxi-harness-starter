package com.summit.runtime.conversation;

import com.summit.core.agent.AgentRequest;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.skill.SkillResume;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Decides where this round's dynamic context belongs.
 *
 * <p>The decision is read from the execution's own messages — not from
 * {@code request.contextSpec.context}, which is only the snapshot the application handed in and
 * never sees the messages the framework appended. Reading the wrong list made every round look
 * like "no dynamic context yet".</p>
 *
 * <p>A change is never answered by rewriting history. Messages that already went to the provider
 * are immutable as far as the cache is concerned: replacing one in the middle invalidates every
 * token after it. So a changed context is <b>inserted</b>, right before this round's user turn and
 * never after it — a background block behind the user turn splits the model's attention away from
 * what was actually asked.</p>
 */
public final class ContextChangeDiscover {

    private ContextChangeDiscover() {
    }

    /**
     * The placement of this round's dynamic context.
     *
     * <p>{@code insert == false} means history stays exactly as it is, which is the common case and
     * the one worth optimising for: an unchanged context costs no token, no cache miss and no
     * allocation.</p>
     */
    public record Layout(boolean insert, int insertIndex, @Nullable UserMessageEntity contract) {

        static Layout unchanged(int insertIndex) {
            return new Layout(false, insertIndex, null);
        }
    }

    /**
     * Computes the placement without touching the list it is given.
     *
     * @param messages the execution's current messages, in send order
     */
    public static Layout layout(List<Message> messages, AgentRequest request, McpToolScope scope,
                                List<SkillResume> skills) {
        UserMessageEntity contract = ContextDynamicAssembler.assemble(
                request.getContextSpec(), scope, skills, request.getTask());

        int insertIndex = insertIndexOf(messages);
        if (contract == null) {
            return Layout.unchanged(insertIndex);
        }
        if (insertIndex > 0 && sameContract(messages.get(insertIndex - 1), contract)) {
            return Layout.unchanged(insertIndex);
        }
        return new Layout(true, insertIndex, contract);
    }

    /**
     * The index this round's user turn currently occupies.
     *
     * <p>Existing dynamic-context messages are skipped, so a retried round does not stack a new
     * block in front of the previous one.</p>
     */
    private static int insertIndexOf(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessageEntity user && !user.hasContextContract()) {
                return i;
            }
        }
        return messages.size();
    }

    private static boolean sameContract(Message previous, UserMessageEntity contract) {
        return previous instanceof UserMessageEntity user
                && user.hasContextContract()
                && user.text().equals(contract.text());
    }
}
