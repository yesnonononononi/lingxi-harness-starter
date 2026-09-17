<script setup>
/**
 * ToolIcon — 黑白极简矢量图标与动效组件
 * 
 * 包含精密 24x24 矢量图标体系与丝滑状态动画：
 *  - running: 极简动态弧环 Spinner (微加速度匀速旋转)
 *  - done: 极简精确对勾 (微弹性动效)
 *  - failed: 极简告警叉号
 *  - 默认态根据 kind 显示纯净的矢量功能图标 (command, read, edit, choice 等)
 */
defineProps({
  kind: { type: String, default: 'command' },
  status: { type: String, default: 'done' },
  size: { type: [Number, String], default: 15 }
})
</script>

<template>
  <span class="tool-icon-wrap" :style="{ width: `${size}px`, height: `${size}px` }">
    <!-- 状态 1: 运行中 (高科技黑白弧环 Spinner) -->
    <svg
      v-if="status === 'running'"
      class="tool-svg spin-arc"
      viewBox="0 0 24 24"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      aria-hidden="true"
    >
      <circle class="arc-track" cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2.2" stroke-opacity="0.18" />
      <path
        class="arc-head"
        d="M12 3a9 9 0 0 1 9 9"
        stroke="currentColor"
        stroke-width="2.2"
        stroke-linecap="round"
      />
    </svg>

    <!-- 状态 2: 完成态 (极简平滑勾选) -->
    <svg
      v-else-if="status === 'done' || status === 'completed'"
      class="tool-svg check-pop"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="2.2"
      stroke-linecap="round"
      stroke-linejoin="round"
      xmlns="http://www.w3.org/2000/svg"
      aria-hidden="true"
    >
      <path d="M20 6L9 17L4 12" />
    </svg>

    <!-- 状态 3: 失败态 (极简叉号) -->
    <svg
      v-else-if="status === 'failed' || status === 'error'"
      class="tool-svg"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="2.2"
      stroke-linecap="round"
      stroke-linejoin="round"
      xmlns="http://www.w3.org/2000/svg"
      aria-hidden="true"
    >
      <path d="M18 6L6 18M6 6l12 12" />
    </svg>

    <!-- 状态 4: 读取文件 (眼睛 / 检视) -->
    <svg
      v-else-if="kind === 'read'"
      class="tool-svg"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="2"
      stroke-linecap="round"
      stroke-linejoin="round"
      xmlns="http://www.w3.org/2000/svg"
      aria-hidden="true"
    >
      <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" />
      <circle cx="12" cy="12" r="3" />
    </svg>

    <!-- 状态 5: 修改文件 (笔刷 / 差异) -->
    <svg
      v-else-if="kind === 'edit'"
      class="tool-svg"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="2"
      stroke-linecap="round"
      stroke-linejoin="round"
      xmlns="http://www.w3.org/2000/svg"
      aria-hidden="true"
    >
      <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7" />
      <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z" />
    </svg>

    <!-- 状态 6: 决策选择 (分支) -->
    <svg
      v-else-if="kind === 'choice'"
      class="tool-svg"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="2"
      stroke-linecap="round"
      stroke-linejoin="round"
      xmlns="http://www.w3.org/2000/svg"
      aria-hidden="true"
    >
      <line x1="6" y1="3" x2="6" y2="15" />
      <circle cx="18" cy="6" r="3" />
      <circle cx="6" cy="18" r="3" />
      <path d="M18 9a9 9 0 0 1-9 9" />
    </svg>

    <!-- 状态 8: 执行终端命令 (默认: 命令行 >_) -->
    <svg
      v-else
      class="tool-svg"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="2"
      stroke-linecap="round"
      stroke-linejoin="round"
      xmlns="http://www.w3.org/2000/svg"
      aria-hidden="true"
    >
      <polyline points="4 17 10 11 4 5" />
      <line x1="12" y1="19" x2="20" y2="19" />
    </svg>
  </span>
</template>

<style scoped>
.tool-icon-wrap {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  vertical-align: middle;
}

.tool-svg {
  width: 100%;
  height: 100%;
  display: block;
}

/* 运行中：平滑极速转动微动效 */
.spin-arc {
  animation: tool-arc-spin 0.75s cubic-bezier(0.45, 0.05, 0.55, 0.95) infinite;
}

@keyframes tool-arc-spin {
  0% { transform: rotate(0deg); }
  100% { transform: rotate(360deg); }
}

/* 完成时微弹跳进入 */
.check-pop {
  animation: tool-check-pop 0.28s cubic-bezier(0.175, 0.885, 0.32, 1.275);
}

@keyframes tool-check-pop {
  0% { transform: scale(0.6); opacity: 0.2; }
  100% { transform: scale(1); opacity: 1; }
}
</style>
