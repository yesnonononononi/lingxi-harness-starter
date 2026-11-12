package com.summit.runtime.conversation;

import com.summit.core.compact.TokenEstimator;
import com.summit.core.conversation.message.Message;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Output budget semantics: the kept part is the prefix, the tail is dropped behind a marker.
 */
class DefaultTokenizerTruncateTest {

    private static final String MARKER = "\n...[OUTPUT_TRUNCATED]...";

    @Test
    void outputWithinTheBudgetIsReturnedUnchanged() {
        String output = "short output";

        assertSame(output, new DefaultTokenizer().truncate(output, 100));
    }

    @Test
    void oversizedOutputKeepsOnlyThePrefix() {
        String output = "A".repeat(600) + "TAIL_MARKER";

        String truncated = new DefaultTokenizer().truncate(output, 100);

        // Fallback estimate: 611 chars / 3 = 203 tokens, so 100 tokens is roughly 300 chars.
        assertEquals("A".repeat(300) + MARKER, truncated);
        assertFalse(truncated.contains("TAIL_MARKER"), "the tail must be dropped, not kept after the marker");
    }

    @Test
    void budgetFollowsTheInjectedEstimator() {
        // One char per token: a 100-token budget leaves exactly 100 chars.
        DefaultTokenizer tokenizer = new DefaultTokenizer(new TokenEstimator() {
            @Override
            public int estimateText(String text) {
                return text.length();
            }

            @Override
            public int estimateMessages(List<Message> messages) {
                return 0;
            }
        });
        String output = "B".repeat(500);

        assertEquals("B".repeat(100) + MARKER, tokenizer.truncate(output, 100));
    }

    @Test
    void emptyOutputStaysEmpty() {
        assertEquals("", new DefaultTokenizer().truncate("", 10));
        assertNull(new DefaultTokenizer().truncate(null, 10));
    }
}
