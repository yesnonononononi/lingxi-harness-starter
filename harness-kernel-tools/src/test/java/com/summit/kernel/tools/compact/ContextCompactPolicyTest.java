package com.summit.kernel.tools.compact;

import com.summit.runtime.agent.AgentConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Compaction properties configure summarization; pruning uses the hard runtime input budget. */
class ContextCompactPolicyTest {

    @Test
    void modelThresholdComesFromTheProperties() {
        ContextCompactToolProperties properties = new ContextCompactToolProperties();
        properties.setModelThreshold(0.9);

        AgentConfig.ProgressiveSqueezePolicy policy =
                new ContextCompactToolAutoConfiguration().progressiveSqueezePolicy(properties);

        assertEquals(0.9, policy.modelSqueeze().threshold());
    }

    @Test
    void defaultModelThresholdMatchesTheDocumentedBand() {
        AgentConfig.ProgressiveSqueezePolicy policy = new ContextCompactToolAutoConfiguration()
                .progressiveSqueezePolicy(new ContextCompactToolProperties());

        assertEquals(0.85, policy.modelSqueeze().threshold());
    }

    @Test
    void boundSummaryBudgetIsPublishedWithoutLegacyPruneTuning() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "lingxi.agent.runtime.tool.context-compact.max-summary-attempts", "2"));
        ContextCompactToolProperties properties = new Binder(source)
                .bind("lingxi.agent.runtime.tool.context-compact", Bindable.of(ContextCompactToolProperties.class)).get();

        AgentConfig.CompactionTuning tuning =
                new ContextCompactToolAutoConfiguration().compactionTuning(properties);

        assertEquals(2, tuning.maxSummaryAttempts());
        assertEquals(2, AgentConfig.builder().maxSummaryAttempts(tuning.maxSummaryAttempts()).build().summaryAttemptBudget());
    }

    @Test
    void unsetSummaryAttemptsStayNull() {
        AgentConfig.CompactionTuning tuning = new ContextCompactToolAutoConfiguration()
                .compactionTuning(new ContextCompactToolProperties());

        assertNull(tuning.maxSummaryAttempts());
        assertEquals(1, AgentConfig.builder().maxSummaryAttempts(tuning.maxSummaryAttempts()).build().summaryAttemptBudget());
    }
}
