<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount, watch } from 'vue'

/**
 * CuteBear —— 会看鼠标的害羞小熊
 * mode:
 *   idle   : 正常/看鼠标（闲置）
 *   watch  : 睁大眼睛盯着看（输入用户名时）
 *   cover  : 两只爪子捂住眼睛（输入密码时）
 */
const props = defineProps({
  mode: { type: String, default: 'idle' },
})

// ---------- SVG 坐标系常量（viewBox 0 0 400 500） ----------
const VBW = 400
const VBH = 500

const EYE_Y = 194
const EYE_X = { L: 150, R: 250 }

const SHOULDER = {
  L: { x: 104, y: 346 },
  R: { x: 296, y: 346 },
}
const PAW_REST = {
  L: { x: 162, y: 400 },
  R: { x: 238, y: 400 },
}
const PAW_COVER = {
  L: { x: EYE_X.L, y: EYE_Y },
  R: { x: EYE_X.R, y: EYE_Y },
}

// ---------- 实时插值状态 ----------
const cur = reactive({
  armL: { ...PAW_REST.L },
  armR: { ...PAW_REST.R },
  lookX: 0,
  lookY: 0,
  scale: 1, // 眼睛放大倍数（watch 时瞪大）
  browY: 0, // 眉毛上移量
  mouth: 0, // 张嘴程度 0~1
  blush: 0.4, // 腮红浓度
  eyeOpa: 1, // 眼睛可见度（cover 时隐藏）
})
const tgt = reactive({
  armL: { ...PAW_REST.L },
  armR: { ...PAW_REST.R },
  lookX: 0,
  lookY: 0,
  scale: 1,
  browY: 0,
  mouth: 0,
  blush: 0.42,
  eyeOpa: 1,
})

const svgRef = ref(null)
let raf = 0

// ---------- 眼睛几何（随模式实时变化） ----------
const eye = computed(() => {
  const watch = props.mode === 'watch'
  const irisR = (11 + (watch ? 3.5 : 0)) * cur.scale
  const whiteR = 24 * cur.scale
  const lx = cur.lookX
  const ly = cur.lookY
  return {
    irisR,
    whiteR,
    L: { x: EYE_X.L + lx, y: EYE_Y + ly },
    R: { x: EYE_X.R + lx, y: EYE_Y + ly },
  }
})

// ---------- 鼠标追踪 ----------
function clampLook(px, py) {
  // 以头部中心为参考点，限制瞳孔的活动范围
  const cx = 200
  const cy = EYE_Y
  const dx = px - cx
  const dy = py - cy
  const dist = Math.hypot(dx, dy)
  if (dist < 1) return { x: 0, y: 0 }
  const avail = Math.max(2, eye.value.whiteR - eye.value.irisR - 2.5)
  const k = Math.min(1, avail / dist)
  return { x: dx * k, y: dy * k }
}

function onMouseMove(e) {
  if (!svgRef.value) return
  const r = svgRef.value.getBoundingClientRect()
  if (!r.width) return
  const mx = ((e.clientX - r.left) * VBW) / r.width
  const my = ((e.clientY - r.top) * VBH) / r.height
  const o = clampLook(mx, my)
  tgt.lookX = o.x
  tgt.lookY = o.y
}
function onMouseLeave() {
  tgt.lookX = 0
  tgt.lookY = 0
}

// ---------- 模式切换 ----------
function applyMode() {
  const m = props.mode
  if (m === 'cover') {
    // 捂住眼睛：双爪抬起盖住双眼，眼睛隐藏
    Object.assign(tgt.armL, PAW_COVER.L)
    Object.assign(tgt.armR, PAW_COVER.R)
    tgt.scale = 1
    tgt.browY = -3
    tgt.mouth = 0.35
    tgt.blush = 0.85
    tgt.eyeOpa = 0
  } else if (m === 'watch') {
    // 盯着看：眼睛瞪大、眉毛上挑、嘴巴微张、眼睛跟鼠标
    Object.assign(tgt.armL, PAW_REST.L)
    Object.assign(tgt.armR, PAW_REST.R)
    tgt.scale = 1.32
    tgt.browY = -7
    tgt.mouth = 1
    tgt.blush = 0.8
    tgt.eyeOpa = 1
  } else {
    // 闲置：放松状态
    Object.assign(tgt.armL, PAW_REST.L)
    Object.assign(tgt.armR, PAW_REST.R)
    tgt.scale = 1
    tgt.browY = 0
    tgt.mouth = 0
    tgt.blush = 0.42
    tgt.eyeOpa = 1
  }
}
watch(() => props.mode, applyMode)

// ---------- 平滑动画循环 ----------
const ease = (a, b, k) => a + (b - a) * k
function tick() {
  const k = 0.16
  const pk = 0.22
  cur.armL.x = ease(cur.armL.x, tgt.armL.x, k)
  cur.armL.y = ease(cur.armL.y, tgt.armL.y, k)
  cur.armR.x = ease(cur.armR.x, tgt.armR.x, k)
  cur.armR.y = ease(cur.armR.y, tgt.armR.y, k)
  cur.lookX = ease(cur.lookX, tgt.lookX, pk)
  cur.lookY = ease(cur.lookY, tgt.lookY, pk)
  cur.scale = ease(cur.scale, tgt.scale, pk)
  cur.browY = ease(cur.browY, tgt.browY, pk)
  cur.mouth = ease(cur.mouth, tgt.mouth, pk)
  cur.blush = ease(cur.blush, tgt.blush, pk)
  cur.eyeOpa = ease(cur.eyeOpa, tgt.eyeOpa, pk)
  raf = requestAnimationFrame(tick)
}

onMounted(() => {
  window.addEventListener('mousemove', onMouseMove)
  document.addEventListener('mouseleave', onMouseLeave)
  applyMode()
  tick()
})
onBeforeUnmount(() => {
  cancelAnimationFrame(raf)
  window.removeEventListener('mousemove', onMouseMove)
  document.removeEventListener('mouseleave', onMouseLeave)
})

// ---------- 手臂（橡皮筋式曲线） ----------
function armPath(arm, side) {
  const s = side === -1 ? SHOULDER.L : SHOULDER.R
  const p = { x: arm.x, y: arm.y }
  const dx = p.x - s.x
  const dy = p.y - s.y
  const len = Math.hypot(dx, dy) || 1
  // 垂直方向的两个候选
  const nx = -dy / len
  const ny = dx / len
  // 选「朝向身体外侧」的那个（左臂朝左、右臂朝右）
  let ox, oy
  if ((side === -1 && nx < 0) || (side === 1 && nx > 0)) {
    ox = nx
    oy = ny
  } else {
    ox = -nx
    oy = -ny
  }
  const bend = 30
  const ex = (s.x + p.x) / 2 + ox * bend
  const ey = (s.y + p.y) / 2 + oy * bend
  return `M ${s.x.toFixed(1)} ${s.y.toFixed(1)} L ${ex.toFixed(1)} ${ey.toFixed(1)} L ${p.x.toFixed(1)} ${p.y.toFixed(1)}`
}

// 供模板使用的小工具
const fmt = (v) => v.toFixed(1)
const armL = computed(() => armPath(cur.armL, -1))
const armR = computed(() => armPath(cur.armR, 1))
const irisLx = computed(() => eye.value.L.x)
const irisLy = computed(() => eye.value.L.y)
const irisRx = computed(() => eye.value.R.x)
const irisRy = computed(() => eye.value.R.y)
</script>

<template>
  <svg
    ref="svgRef"
    viewBox="0 0 400 500"
    class="bear-svg"
    :style="{ width: '100%', height: '100%' }"
    role="img"
    aria-label="一只害羞的小熊"
  >
    <defs>
      <radialGradient id="furG" cx="50%" cy="34%" r="82%">
        <stop offset="0%" stop-color="#e3a963" />
        <stop offset="62%" stop-color="#c57f3f" />
        <stop offset="100%" stop-color="#aa632c" />
      </radialGradient>
      <radialGradient id="pawG" cx="50%" cy="38%" r="78%">
        <stop offset="0%" stop-color="#c07a40" />
        <stop offset="100%" stop-color="#94501f" />
      </radialGradient>
    </defs>

    <!-- 整体呼吸起伏 -->
    <g class="bob">
      <!-- 地面影子 -->
      <ellipse cx="200" cy="486" rx="132" ry="15" fill="#0b0f24" opacity="0.34" />

      <!-- 身体 -->
      <ellipse cx="200" cy="382" rx="140" ry="120" fill="url(#furG)" />
      <!-- 肚皮 -->
      <ellipse cx="200" cy="416" rx="88" ry="60" fill="#f9ecda" />
      <ellipse cx="200" cy="428" rx="58" ry="34" fill="#fdf5ea" />

      <!-- 两只小脚 -->
      <ellipse cx="110" cy="452" rx="40" ry="24" fill="#a5632e" />
      <ellipse cx="290" cy="452" rx="40" ry="24" fill="#a5632e" />
      <ellipse cx="110" cy="456" rx="15" ry="10" fill="#f0dcb8" />
      <ellipse cx="290" cy="456" rx="15" ry="10" fill="#f0dcb8" />

      <!-- 耳朵（画在头后面） -->
      <circle cx="112" cy="116" r="50" fill="url(#furG)" />
      <circle cx="288" cy="116" r="50" fill="url(#furG)" />
      <circle cx="116" cy="124" r="23" fill="#f2d0a0" />
      <circle cx="284" cy="124" r="23" fill="#f2d0a0" />

      <!-- 头 -->
      <ellipse cx="200" cy="196" rx="124" ry="112" fill="url(#furG)" />

      <!-- 腮红 -->
      <ellipse cx="120" cy="240" rx="17" ry="12" fill="#f49b92" :opacity="fmt(cur.blush)" />
      <ellipse cx="280" cy="240" rx="17" ry="12" fill="#f49b92" :opacity="fmt(cur.blush)" />

      <!-- 嘴部（奶油色口鼻区域） -->
      <ellipse cx="200" cy="240" rx="60" ry="38" fill="#f9ecda" />
      <!-- 鼻子 -->
      <ellipse cx="200" cy="224" rx="16" ry="11" fill="#4e3020" />
      <ellipse cx="196" cy="220" rx="4" ry="2.6" fill="#ffffff" opacity="0.8" />
      <!-- 微笑 -->
      <path
        d="M178 250 Q 188 260 200 252 Q 212 260 222 250"
        fill="none"
        stroke="#5a3a24"
        stroke-width="5"
        stroke-linecap="round"
        :opacity="fmt(1 - cur.mouth)"
      />
      <!-- 惊讶张嘴 -->
      <g :opacity="fmt(cur.mouth)">
        <ellipse cx="200" cy="262" rx="9" ry="13" fill="#5a3a24" />
        <ellipse cx="200" cy="267" rx="6" ry="5.5" fill="#f08a80" />
      </g>

      <!-- 眉毛（watch 时上挑） -->
      <g :transform="`translate(0 ${fmt(cur.browY)})`">
        <path d="M130 164 Q 150 157 170 162" fill="none" stroke="#4e3020" stroke-width="7" stroke-linecap="round" />
        <path d="M230 162 Q 250 157 270 164" fill="none" stroke="#4e3020" stroke-width="7" stroke-linecap="round" />
      </g>

      <!-- 眼睛（cover 时隐藏） -->
      <g :opacity="fmt(Math.max(0, cur.eyeOpa))">
        <!-- 左眼 -->
        <g>
          <circle :cx="EYE_X.L" :cy="EYE_Y" :r="fmt(eye.whiteR)" fill="#ffffff" />
          <circle :cx="fmt(irisLx)" :cy="fmt(irisLy)" :r="fmt(eye.irisR)" fill="#3b2a1e" />
          <circle :cx="fmt(irisLx)" :cy="fmt(irisLy)" :r="fmt(eye.irisR * 0.5)" fill="#170f0a" />
          <circle :cx="fmt(irisLx - eye.irisR * 0.35)" :cy="fmt(irisLy - eye.irisR * 0.4)" :r="fmt(Math.max(2, eye.irisR * 0.28))" fill="#ffffff" opacity="0.95" />
          <circle :cx="fmt(irisLx + eye.irisR * 0.38)" :cy="fmt(irisLy + eye.irisR * 0.2)" :r="fmt(Math.max(1.2, eye.irisR * 0.16))" fill="#ffffff" opacity="0.85" />
        </g>
        <!-- 右眼 -->
        <g>
          <circle :cx="EYE_X.R" :cy="EYE_Y" :r="fmt(eye.whiteR)" fill="#ffffff" />
          <circle :cx="fmt(irisRx)" :cy="fmt(irisRy)" :r="fmt(eye.irisR)" fill="#3b2a1e" />
          <circle :cx="fmt(irisRx)" :cy="fmt(irisRy)" :r="fmt(eye.irisR * 0.5)" fill="#170f0a" />
          <circle :cx="fmt(irisRx - eye.irisR * 0.35)" :cy="fmt(irisRy - eye.irisR * 0.4)" :r="fmt(Math.max(2, eye.irisR * 0.28))" fill="#ffffff" opacity="0.95" />
          <circle :cx="fmt(irisRx + eye.irisR * 0.38)" :cy="fmt(irisRy + eye.irisR * 0.2)" :r="fmt(Math.max(1.2, eye.irisR * 0.16))" fill="#ffffff" opacity="0.85" />
        </g>
      </g>

      <!-- watch 时眼睛周围的小星星 -->
      <g v-if="mode === 'watch'">
        <g transform="translate(108 146)">
          <path class="twinkle" d="M0 -9 L2 -2 L9 0 L2 2 L0 9 L-2 2 L-9 0 L-2 -2 Z" fill="#ffe7a3" />
        </g>
        <g transform="translate(292 146)">
          <path class="twinkle" d="M0 -9 L2 -2 L9 0 L2 2 L0 9 L-2 2 L-9 0 L-2 -2 Z" fill="#ffe7a3" />
        </g>
      </g>

      <!-- 手臂（画在头上方，抬起时自然盖向眼睛） -->
      <path :d="armL" fill="none" stroke="#c07a40" stroke-width="42" stroke-linecap="round" stroke-linejoin="round" />
      <path :d="armR" fill="none" stroke="#c07a40" stroke-width="42" stroke-linecap="round" stroke-linejoin="round" />

      <!-- 左爪子 -->
      <g>
        <circle :cx="fmt(cur.armL.x)" :cy="fmt(cur.armL.y)" r="33" fill="url(#pawG)" />
        <circle :cx="fmt(cur.armL.x)" :cy="fmt(cur.armL.y)" r="33" fill="none" stroke="#7c4418" stroke-width="3" opacity="0.25" />
        <ellipse :cx="fmt(cur.armL.x)" :cy="fmt(cur.armL.y + 3)" rx="15" ry="11" fill="#f0dcb8" opacity="0.95" />
        <circle :cx="fmt(cur.armL.x - 8)" :cy="fmt(cur.armL.y - 22)" r="5" fill="#f0dcb8" />
        <circle :cx="fmt(cur.armL.x)" :cy="fmt(cur.armL.y - 26)" r="5" fill="#f0dcb8" />
        <circle :cx="fmt(cur.armL.x + 8)" :cy="fmt(cur.armL.y - 22)" r="5" fill="#f0dcb8" />
      </g>
      <!-- 右爪子 -->
      <g>
        <circle :cx="fmt(cur.armR.x)" :cy="fmt(cur.armR.y)" r="33" fill="url(#pawG)" />
        <circle :cx="fmt(cur.armR.x)" :cy="fmt(cur.armR.y)" r="33" fill="none" stroke="#7c4418" stroke-width="3" opacity="0.25" />
        <ellipse :cx="fmt(cur.armR.x)" :cy="fmt(cur.armR.y + 3)" rx="15" ry="11" fill="#f0dcb8" opacity="0.95" />
        <circle :cx="fmt(cur.armR.x - 8)" :cy="fmt(cur.armR.y - 22)" r="5" fill="#f0dcb8" />
        <circle :cx="fmt(cur.armR.x)" :cy="fmt(cur.armR.y - 26)" r="5" fill="#f0dcb8" />
        <circle :cx="fmt(cur.armR.x + 8)" :cy="fmt(cur.armR.y - 22)" r="5" fill="#f0dcb8" />
      </g>
    </g>
  </svg>
</template>

<style scoped>
.bear-svg {
  display: block;
  filter: drop-shadow(0 22px 30px rgba(15, 23, 42, 0.18));
}

/* 呼吸起伏 */
.bob {
  animation: bob 4.2s ease-in-out infinite;
}
@keyframes bob {
  0%,
  100% {
    transform: translateY(0);
  }
  50% {
    transform: translateY(4px);
  }
}

/* 小星星闪烁 */
.twinkle {
  transform-box: fill-box;
  transform-origin: center;
  animation: twinkle 1.3s ease-in-out infinite;
}
@keyframes twinkle {
  0%,
  100% {
    opacity: 0.2;
    transform: scale(0.6) rotate(0deg);
  }
  50% {
    opacity: 1;
    transform: scale(1.05) rotate(80deg);
  }
}
</style>
