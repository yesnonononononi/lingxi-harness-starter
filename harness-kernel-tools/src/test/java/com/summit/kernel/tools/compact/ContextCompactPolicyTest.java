package com.summit.kernel.tools.compact;

import com.summit.runtime.agent.AgentConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 压缩阈值与预算真的由 context-compact 命名空间驱动。
 *
 * <p>本地截断档已经删除，这里钉住的是现在这条链路：属性对象 → {@code ProgressiveSqueezePolicy}
 * （模型摘要档唯一的阈值）以及属性对象 → {@code CompactionTuning}（修剪预算与摘要尝试上限）。</p>
 */
class ContextCompactPolicyTest {

    @Test
    @DisplayName("模型摘要阈值取自 context-compact 命名空间")
    void modelThresholdComesFromTheProperties() {
        ContextCompactToolProperties properties = new ContextCompactToolProperties();
        properties.setModelThreshold(0.9);

        AgentConfig.ProgressiveSqueezePolicy policy =
                new ContextCompactToolAutoConfiguration().progressiveSqueezePolicy(properties);

        assertEquals(0.9, policy.modelSqueeze().threshold(), "模型深度压缩档");
    }

    @Test
    @DisplayName("默认摘要阈值就是文档里的 0.85")
    void defaultModelThresholdMatchesTheDocumentedBand() {
        AgentConfig.ProgressiveSqueezePolicy policy = new ContextCompactToolAutoConfiguration()
                .progressiveSqueezePolicy(new ContextCompactToolProperties());

        assertEquals(0.85, policy.modelSqueeze().threshold());
    }

    @Test
    @DisplayName("修剪预算与摘要尝试上限经 CompactionTuning 透传，空值表示用运行时默认")
    void tuningIsPublishedFromTheProperties() {
        ContextCompactToolProperties properties = new ContextCompactToolProperties();
        properties.setPruneTargetRatio(0.35);
        properties.setFullSegmentCount(6);
        properties.setFullToolOutputCap(2_000);
        properties.setMaxSummaryAttempts(2);

        AgentConfig.CompactionTuning tuning =
                new ContextCompactToolAutoConfiguration().compactionTuning(properties);

        assertEquals(0.35, tuning.pruneTargetRatio());
        assertEquals(6, tuning.fullSegmentCount());
        assertEquals(2_000, tuning.fullToolOutputCap());
        assertEquals(2, tuning.maxSummaryAttempts());
    }

    @Test
    @DisplayName("未配置的摘要尝试上限保持 null，由 AgentConfig 解释为单次")
    void unsetSummaryAttemptsStayNull() {
        AgentConfig.CompactionTuning tuning = new ContextCompactToolAutoConfiguration()
                .compactionTuning(new ContextCompactToolProperties());

        assertNull(tuning.maxSummaryAttempts(), "未配置即 null，框架不擅自给默认值");
        assertEquals(0.40, tuning.pruneTargetRatio(), "修剪目标比例的初始值");
    }
}
