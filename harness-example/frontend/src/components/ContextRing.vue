<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'

/**
 * ContextRing — 上下文用量环形图。
 *
 * 圆环弧长按 ratio 绘制，颜色随水位（<60% 绿 / 60~85% 琥珀 / ≥85% 红）变化；
 * 悬停或键盘聚焦时弹出深色信息气泡，展示百分比与 token 明细。
 *
 * ratio / tokenCount 变化时（页面挂载拉取、压缩完成事件、运行中周期上报）用
 * requestAnimationFrame 做缓动插值，避免弧长与数字“跳变”。
 */
const props = defineProps({
  // 已用比例，通常 0~1；超出上限时允许 > 1（环画满并转为告警色）
  ratio: { type: Number, default: 0 },
  tokenCount: { type: Number, default: 0 },
  maxTokens: { type: Number, default: 0 },
  size: { type: Number, default: 20 },
})

const STROKE = 2
const TWEEN_MS = 520

const radius = computed(() => (props.size - STROKE) / 2)
const circumference = computed(() => 2 * Math.PI * radius.value)

// ---------- 数值缓动：测量值插值，上限是配置值直接落地 ----------
const displayRatio = ref(props.ratio || 0)
const displayTokens = ref(props.tokenCount || 0)
const displayMax = ref(props.maxTokens || 0)
let raf = 0

function tweenTo() {
  cancelAnimationFrame(raf)
  displayMax.value = props.maxTokens || 0
  const fromRatio = displayRatio.value
  const fromTokens = displayTokens.value
  const toRatio = props.ratio || 0
  const toTokens = props.tokenCount || 0
  if (fromRatio === toRatio && fromTokens === toTokens) return
  const start = performance.now()
  const step = (now) => {
    const p = Math.min(1, (now - start) / TWEEN_MS)
    const e = 1 - Math.pow(1 - p, 3) // easeOutCubic
    displayRatio.value = fromRatio + (toRatio - fromRatio) * e
    displayTokens.value = fromTokens + (toTokens - fromTokens) * e
    if (p < 1) raf = requestAnimationFrame(step)
  }
  raf = requestAnimationFrame(step)
}

watch(() => [props.ratio, props.tokenCount, props.maxTokens], tweenTo)
onBeforeUnmount(() => cancelAnimationFrame(raf))

// ---------- 渲染派生值 ----------
const clamped = computed(() => Math.min(1, Math.max(0, displayRatio.value)))
const dashOffset = computed(() => circumference.value * (1 - clamped.value))
const level = computed(() => {
  const r = displayRatio.value
  if (r >= 0.85) return 'danger'
  if (r >= 0.6) return 'warn'
  return 'ok'
})

// 上限缺失（接口未上报 / 未配置）时不再显示 “0 / 0”，避免看起来像坏了
const hasCap = computed(() => displayMax.value > 0)

// 66.6K / 1000.0K：不足 1K 直接显示原值
function formatTokens(n) {
  const v = Math.max(0, Math.round(Number(n) || 0))
  return v < 1000 ? String(v) : (v / 1000).toFixed(1) + 'K'
}

const percentText = computed(() => `${(Math.max(0, displayRatio.value) * 100).toFixed(1)}%`)
const detailText = computed(() =>
  hasCap.value
    ? `${formatTokens(displayTokens.value)} / ${formatTokens(displayMax.value)} tokens`
    : '等待用量上报'
)
const ariaLabel = computed(() =>
  hasCap.value
    ? `上下文已使用 ${percentText.value}，${formatTokens(displayTokens.value)} / ${formatTokens(displayMax.value)} tokens`
    : '上下文用量暂不可用'
)
</script>

<template>
  <div class="ctx-ring" :class="level" tabindex="0" role="img" :aria-label="ariaLabel">
    <svg :width="size" :height="size" :viewBox="`0 0 ${size} ${size}`" aria-hidden="true">
      <circle
        class="ring-track"
        :cx="size / 2"
        :cy="size / 2"
        :r="radius"
        :stroke-width="STROKE"
        fill="none"
      />
      <circle
        class="ring-arc"
        :cx="size / 2"
        :cy="size / 2"
        :r="radius"
        :stroke-width="STROKE"
        fill="none"
        stroke-linecap="round"
        :stroke-dasharray="circumference"
        :stroke-dashoffset="dashOffset"
        :transform="`rotate(-90 ${size / 2} ${size / 2})`"
      />
    </svg>
    <span class="ring-tip" role="tooltip">
      <span class="tip-head">
        <b>{{ percentText }}</b>
        <em>上下文已使用</em>
      </span>
      <span class="tip-sub">{{ detailText }}</span>
    </span>
  </div>
</template>

<style scoped>
.ctx-ring {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: 6px;
  cursor: default;
  outline: none;
  transition: background 0.18s ease;
}
.ctx-ring:hover,
.ctx-ring:focus-visible {
  background: #f1f2f7;
}

.ring-track {
  stroke: #e2e5ee;
}

.ring-arc {
  stroke: #22c55e;
  transition: stroke 0.3s ease;
}
.ctx-ring.warn .ring-arc { stroke: #f0a020; }
.ctx-ring.danger .ring-arc { stroke: #e5484d; }

/* 深色信息气泡：两行排版 + 小箭头，比整块粗体黑底更轻 */
.ring-tip {
  position: absolute;
  right: 0;
  bottom: calc(100% + 9px);
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 7px 10px;
  border-radius: 8px;
  background: #202433;
  box-shadow: 0 8px 24px rgba(24, 28, 45, 0.22);
  white-space: nowrap;
  pointer-events: none;
  opacity: 0;
  transform: translateY(4px);
  transition: opacity 0.16s ease, transform 0.16s ease;
}
.ring-tip::after {
  content: '';
  position: absolute;
  right: 8px;
  top: 100%;
  border: 5px solid transparent;
  border-top-color: #202433;
}
.ctx-ring:hover .ring-tip,
.ctx-ring:focus-visible .ring-tip {
  opacity: 1;
  transform: translateY(0);
}

.tip-head {
  display: flex;
  align-items: baseline;
  gap: 6px;
}
.tip-head b {
  font-size: 12.5px;
  font-weight: 600;
  color: #ffffff;
}
.tip-head em {
  font-style: normal;
  font-size: 11px;
  color: #9aa0b4;
}
.tip-sub {
  font-size: 11px;
  color: #7f869c;
}

@media (prefers-reduced-motion: reduce) {
  .ring-arc,
  .ctx-ring,
  .ring-tip {
    transition: none;
  }
}
</style>
