package com.summit.runtime.compact;

import com.summit.core.compact.ContextUsageMetric;
import com.summit.core.compact.Tokenizer;
import com.summit.core.conversation.message.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * Scripted {@link Tokenizer} for the compaction tests.
 *
 * <p>The compaction state machine is driven by the context ratio, and a test must be able to say
 * "over the threshold when the band is judged, under it right after the rebuild" without building
 * payloads of a hundred thousand tokens. The ratio is therefore scripted per read: each call
 * consumes the next scripted value and the last value repeats forever.</p>
 *
 * <p>Only {@link #calcCurrentTokenRatio} is meaningful here; token counting returns zero and
 * truncation is the identity, because no test in this class exercises size-based behaviour.</p>
 */
final class StubContextTokenizer implements Tokenizer {

    private final List<Double> scripted = new ArrayList<>();
    private int reads = 0;

    /**
     * @param ratios the ratios returned by successive reads; the last one repeats
     */
    StubContextTokenizer(double... ratios) {
        if (ratios.length == 0) {
            throw new IllegalArgumentException("at least one scripted ratio is required");
        }
        for (double ratio : ratios) {
            scripted.add(ratio);
        }
    }

    /** Replaces the script: every following read returns this ratio. */
    void ratio(double ratio) {
        scripted.clear();
        scripted.add(ratio);
        reads = 0;
    }

    @Override
    public int count(List<Message> messages) {
        return 0;
    }

    @Override
    public String truncate(String output, int maxOutput) {
        return output;
    }

    @Override
    public double calcCurrentTokenRatio(List<Message> messages, int maxTokens) {
        double value = scripted.get(Math.min(reads, scripted.size() - 1));
        reads++;
        return value;
    }

    @Override
    public ContextUsageMetric usage(List<Message> messages, Integer maxTokens) {
        return ContextUsageMetric.of(0, maxTokens == null ? 0 : maxTokens);
    }
}
