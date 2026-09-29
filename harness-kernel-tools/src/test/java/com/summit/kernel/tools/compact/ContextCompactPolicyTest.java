package com.summit.kernel.tools.compact;

import com.summit.runtime.agent.AgentConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 多段压缩阈值真的由 yaml 属性驱动。
 *
 * <p>阈值此前散在 `lingxi.agent.model.conf.chat` 下、并且还有一个同名却无人读取的死键。这里钉住的是
 * 现在这条链路：属性对象 → {@code ProgressiveSqueezePolicy} → {@code BoundaryChecker} 判档用的两个阈值
 * 与每趟截断轮次。</p>
 */
class ContextCompactPolicyTest {

    @Test
    @DisplayName("两档阈值与截断轮次都取自 context-compact 命名空间")
    void thresholdsComeFromTheProperties() {
        ContextCompactToolProperties properties = new ContextCompactToolProperties();
        properties.setTruncateThreshold(0.5);
        properties.setModelThreshold(0.9);
        properties.setTruncateRounds(3);

        AgentConfig.ProgressiveSqueezePolicy policy =
                new ContextCompactToolAutoConfiguration().progressiveSqueezePolicy(properties);

        assertEquals(0.5, policy.truncateSqueeze().threshold(), "本地截断档");
        assertEquals(3, policy.truncateSqueeze().expectTruncateTurn(), "每趟截断轮次");
        assertEquals(0.9, policy.modelSqueeze().threshold(), "模型深度压缩档");
    }

    @Test
    @DisplayName("默认值就是文档里的两档 0.7 / 0.85 与 5 轮")
    void defaultsMatchTheDocumentedBands() {
        AgentConfig.ProgressiveSqueezePolicy policy = new ContextCompactToolAutoConfiguration()
                .progressiveSqueezePolicy(new ContextCompactToolProperties());

        assertEquals(0.7, policy.truncateSqueeze().threshold());
        assertEquals(5, policy.truncateSqueeze().expectTruncateTurn());
        assertEquals(0.85, policy.modelSqueeze().threshold());
    }
}
