package com.summit.core.compact;

import com.summit.core.agent.Execution;

public interface CompactSummaryApplier {
    boolean apply(String rawOutput, Execution execution);
}
