package com.summit.core.conf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 档位语义的唯一真源：Anthropic 上 xhigh/minimal 与 high/low 同义，OpenAI 协议原样下发。
 *
 * <p>这些规则同时被适配器（决定发什么）与业务侧（决定 UI 列几档、配置合不合法）消费，
 * 所以必须钉在框架里测，而不是各消费方各测一份自己的副本。</p>
 */
class ReasoningEffortPlanTest {

    @Test
    @DisplayName("OpenAI 协议原样保留全部档位")
    void openAiKeepsEveryLevel() {
        assertEquals(ReasoningEffortPlan.LEVELS, ReasoningEffortPlan.distinct("openai", "gpt-5"));
        assertEquals("xhigh", ReasoningEffortPlan.canonical("openai", "gpt-5", "xhigh"));
        assertEquals("minimal", ReasoningEffortPlan.canonical("openai", "gpt-5", "minimal"));
        assertNull(ReasoningEffortPlan.invalidReason("openai", "gpt-5", "max", 1024),
                "OpenAI 协议下预算由服务端裁决，框架不做交叉校验");
    }

    @Test
    @DisplayName("Anthropic：xhigh 折 high、minimal 折 low，且不再单列这两个档")
    void anthropicCollapsesIndistinguishableLevels() {
        for (String model : List.of("claude-3-5-sonnet-20241022", "claude-sonnet-4-5", "claude-sonnet-4-6")) {
            assertEquals("high", ReasoningEffortPlan.canonical("anthropic", model, "xhigh"), model);
            assertEquals("low", ReasoningEffortPlan.canonical("anthropic", model, "minimal"), model);
            List<String> levels = ReasoningEffortPlan.distinct("anthropic", model);
            assertFalse(levels.contains("xhigh"), "xhigh 在 " + model + " 上不产生独立请求，不能列出来");
            assertFalse(levels.contains("minimal"), "minimal 在 " + model + " 上不产生独立请求，不能列出来");
            assertTrue(levels.contains("max"), "max 是真实可区分的档位: " + model);
        }
        assertEquals(List.of("none", "low", "medium", "high", "max"),
                ReasoningEffortPlan.distinct("anthropic", "claude-sonnet-4-6"));
    }

    @Test
    @DisplayName("manual thinking 判定与 Opus 4.5 的例外")
    void manualThinkingDetection() {
        assertTrue(ReasoningEffortPlan.manualThinking("claude-3-5-haiku-latest"));
        assertTrue(ReasoningEffortPlan.manualThinking("claude-sonnet-4-5"));
        assertTrue(ReasoningEffortPlan.manualThinking("claude-opus-4-1-20250805"));
        assertFalse(ReasoningEffortPlan.manualThinking("claude-sonnet-4-6"), "4.6 走 adaptive thinking");
        assertFalse(ReasoningEffortPlan.manualThinking("gpt-5"));
        assertTrue(ReasoningEffortPlan.supportsNativeEffort("claude-opus-4-5"));
        assertFalse(ReasoningEffortPlan.supportsNativeEffort("claude-sonnet-4-5"));
    }

    @Test
    @DisplayName("档位与输出上限的交叉校验：思考预算必须小于输出上限")
    void effortAndOutputBudgetAreCrossChecked() {
        // 手动 thinking 的模型才受这条约束
        assertNull(ReasoningEffortPlan.invalidReason("anthropic", "claude-sonnet-4-5", "max", 32768));
        assertTrue(ReasoningEffortPlan.invalidReason("anthropic", "claude-sonnet-4-5", "max", 4096).contains("16384"),
                "提示要说清预算与上限各是多少");
        assertTrue(ReasoningEffortPlan.invalidReason("anthropic", "claude-sonnet-4-5", "low", 1024) != null,
                "预算 1024 不小于上限 1024，必须拦下 —— 否则建模型时才抛英文异常");
        // none 关闭思考，不占预算
        assertNull(ReasoningEffortPlan.invalidReason("anthropic", "claude-sonnet-4-5", "none", 512));
        // adaptive 模型不传预算，不受上限约束
        assertNull(ReasoningEffortPlan.invalidReason("anthropic", "claude-sonnet-4-6", "max", 4096));
        // 未知档位一眼看出是哪个值
        assertTrue(ReasoningEffortPlan.invalidReason("openai", "gpt-5", "ultra", 32768).contains("ultra"));
        assertEquals(List.of("none"), ReasoningEffortPlan.usable("anthropic", "claude-sonnet-4-5", 1024));
    }
}
