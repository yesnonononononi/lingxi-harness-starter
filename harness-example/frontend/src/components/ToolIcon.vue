<script setup>
// 工具调用图标：
//   read    -> 眼睛（读取文件）
//   edit    -> 环形（修改文件）：执行中与 command 一致匀速旋转，完成后静止
//   command -> 执行中：环形 loading 旋转；完成：√
defineProps({
  kind: { type: String, default: 'command' },
  // command：running 时转成旋转的 loading，done 显示 √
  // edit：running 时同样旋转（动画样式对齐 command），done 保持环形
  status: { type: String, default: 'done' },
})
</script>

<template>
  <!-- 读取文件：眼睛 -->
  <svg
    v-if="kind === 'read'"
    class="tool-svg"
    viewBox="0 0 1024 1024"
    width="14"
    height="14"
    fill="currentColor"
    xmlns="http://www.w3.org/2000/svg"
    aria-hidden="true"
    focusable="false"
  >
    <path d="M515.2 224c-307.2 0-492.8 313.6-492.8 313.6s214.4 304 492.8 304 492.8-304 492.8-304S822.4 224 515.2 224zM832 652.8c-102.4 86.4-211.2 140.8-320 140.8s-217.6-51.2-320-140.8c-35.2-32-70.4-64-99.2-99.2-6.4-6.4-9.6-12.8-16-19.2 3.2-6.4 9.6-12.8 12.8-19.2 25.6-35.2 57.6-70.4 92.8-102.4 99.2-89.6 208-144 329.6-144s230.4 54.4 329.6 144c35.2 32 64 67.2 92.8 102.4 3.2 6.4 9.6 12.8 12.8 19.2-3.2 6.4-9.6 12.8-16 19.2C902.4 585.6 870.4 620.8 832 652.8z" />
    <path d="M512 345.6c-96 0-169.6 76.8-169.6 169.6 0 96 76.8 169.6 169.6 169.6 96 0 169.6-76.8 169.6-169.6C681.6 422.4 604.8 345.6 512 345.6zM512 640c-67.2 0-121.6-54.4-121.6-121.6 0-67.2 54.4-121.6 121.6-121.6 67.2 0 121.6 54.4 121.6 121.6C633.6 582.4 579.2 640 512 640z" />
  </svg>

  <!-- 执行命令 · 进行中：环形 loading，匀速旋转 -->
  <svg
    v-else-if="kind === 'command' && status === 'running'"
    class="tool-svg spin"
    viewBox="0 0 1024 1024"
    width="14"
    height="14"
    fill="currentColor"
    xmlns="http://www.w3.org/2000/svg"
    aria-hidden="true"
    focusable="false"
  >
    <path d="M512 149.333333a362.709333 362.709333 0 1 0 362.666667 362.666667H938.666667A426.666667 426.666667 0 1 1 512 85.333333v64z" />
  </svg>

  <!-- 执行命令 · 已完成：√ -->
  <svg
    v-else-if="kind === 'command'"
    class="tool-svg"
    viewBox="0 0 1024 1024"
    width="14"
    height="14"
    fill="currentColor"
    xmlns="http://www.w3.org/2000/svg"
    aria-hidden="true"
    focusable="false"
  >
    <path d="M912 190h-69.9c-9.8 0-19.1 4.5-25.1 12.2L404.7 724.5 212.6 487.9c-6-7.7-15.3-12.2-25.1-12.2H117.4c-11.9 0-18.6 13.9-11.3 22.9L381.9 824.5c12.1 15.1 35 15.1 47.2 0l494.2-611.6c7.3-9-.6-22.9-11.3-22.9z" />
  </svg>

  <!-- 其余（修改文件等）：环形；执行中的「修改文件」复用与「执行命令」完全相同的旋转动画 -->
  <svg
    v-else
    class="tool-svg"
    :class="{ spin: kind === 'edit' && status === 'running' }"
    viewBox="0 0 1024 1024"
    width="14"
    height="14"
    fill="currentColor"
    xmlns="http://www.w3.org/2000/svg"
    aria-hidden="true"
    focusable="false"
  >
    <path d="M512 149.333333a362.709333 362.709333 0 1 0 362.666667 362.666667H938.666667A426.666667 426.666667 0 1 1 512 85.333333v64z" />
  </svg>
</template>

<style scoped>
/* 执行命令进行中：绕自身中心匀速旋转 */
.tool-svg.spin {
  transform-origin: 50% 50%;
  animation: tool-svg-spin .9s linear infinite;
}

@keyframes tool-svg-spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
</style>
