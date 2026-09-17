<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount } from 'vue'

/**
 * WelcomeWidget — interactive empty-state widget.
 * Pure CSS animation + lightweight interactions, no third-party dependency:
 *  - hover: ripples speed up, 3D parallax tilt, pointer feedback
 *  - press: shrink
 *  - click / Enter / Space: ripple burst + bounce + caption/face cycling
 *  - degrades to a static view when prefers-reduced-motion is set
 */
const CAPTIONS = [
  '发送一条指令，剩下的交给我 🚀',
  '随时待命，想让我先看看哪个文件？',
  '我能读代码、改代码、跑命令，试试就知道',
  '左侧还能切换会话与执行模式 ✨',
]
const FACES = ['灵', '嗨', '✨', '♪']

const hover = ref(false)
const pressed = ref(false)
const popped = ref(false)
const captionIndex = ref(0)
const rootEl = ref(null)
const bursts = ref([])
let burstSeq = 0

const reduceMotion =
  typeof window !== 'undefined' &&
  window.matchMedia &&
  window.matchMedia('(prefers-reduced-motion: reduce)').matches

const caption = computed(() => CAPTIONS[captionIndex.value])
const face = computed(() => FACES[captionIndex.value])

// ---------- 3D parallax (pointer tilt) ----------
const tilt = reactive({ x: 0, y: 0, tx: 0, ty: 0 })
let raf = 0

function onMove(e) {
  if (reduceMotion || !rootEl.value) return
  const r = rootEl.value.getBoundingClientRect()
  if (!r.width || !r.height) return
  const px = (e.clientX - r.left) / r.width - 0.5
  const py = (e.clientY - r.top) / r.height - 0.5
  tilt.tx = -py * 12 // rotateX
  tilt.ty = px * 14 // rotateY
}

function onLeave() {
  hover.value = false
  pressed.value = false
  tilt.tx = 0
  tilt.ty = 0
}

function tick() {
  tilt.x += (tilt.tx - tilt.x) * 0.14
  tilt.y += (tilt.ty - tilt.y) * 0.14
  raf = requestAnimationFrame(tick)
}

const stageStyle = computed(() => ({
  transform: `rotateX(${tilt.x.toFixed(2)}deg) rotateY(${tilt.y.toFixed(2)}deg)`,
}))

// ---------- click / keyboard interaction ----------
function interact() {
  captionIndex.value = (captionIndex.value + 1) % CAPTIONS.length
  // restart the pop animation (remove the class, re-add it on the next frame)
  popped.value = false
  requestAnimationFrame(() => {
    popped.value = true
  })
  spawnBursts()
}

function spawnBursts() {
  const el = rootEl.value
  if (!el || reduceMotion) return
  const r = el.getBoundingClientRect()
  const cx = r.width / 2
  const cy = r.height / 2
  const created = []
  for (let i = 0; i < 3; i++) {
    const id = ++burstSeq
    bursts.value.push({ id, x: cx, y: cy, delay: i * 90 })
    created.push(id)
  }
  setTimeout(() => {
    bursts.value = bursts.value.filter((b) => !created.includes(b.id))
  }, 1000)
}

function burstStyle(b) {
  return {
    left: `${b.x}px`,
    top: `${b.y}px`,
    animationDelay: `${b.delay}ms`,
  }
}

function onPointerDown() {
  pressed.value = true
}

function releasePointer() {
  pressed.value = false
}

onMounted(() => {
  if (!reduceMotion) raf = requestAnimationFrame(tick)
  window.addEventListener('pointerup', releasePointer)
  window.addEventListener('blur', releasePointer)
})

onBeforeUnmount(() => {
  if (raf) cancelAnimationFrame(raf)
  window.removeEventListener('pointerup', releasePointer)
  window.removeEventListener('blur', releasePointer)
})
</script>

<template>
  <div
    ref="rootEl"
    class="ww"
    :class="{ hover, pressed, popped }"
    role="button"
    tabindex="0"
    aria-label="灵犀交互小组件，点击可与它互动"
    @pointerenter="hover = true"
    @pointerleave="onLeave"
    @pointermove="onMove"
    @pointerdown="onPointerDown"
    @click="interact"
    @keydown.enter.prevent="interact"
    @keydown.space.prevent="interact"
  >
    <div class="ww-stage" :style="stageStyle">
      <!-- idle ripples: staggered expansion -->
      <span class="ring r1"></span>
      <span class="ring r2"></span>
      <span class="ring r3"></span>

      <!-- orbiting particles -->
      <span class="orbit o1"><i class="dot"></i></span>
      <span class="orbit o2"><i class="dot"></i></span>
      <span class="orbit o3"><i class="dot"></i></span>

      <!-- center orb: breathing + bounce feedback -->
      <div class="orb-float">
        <div class="orb">
          <span class="orb-glow"></span>
          <span class="orb-face">{{ face }}</span>
        </div>
      </div>

      <!-- ripples spawned on click -->
      <span v-for="b in bursts" :key="b.id" class="burst" :style="burstStyle(b)"></span>
    </div>

    <p class="ww-caption">👋 {{ caption }}</p>
  </div>
</template>

<style scoped>
.ww {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
  cursor: pointer;
  user-select: none;
  -webkit-user-select: none;
  perspective: 640px;
  outline: none;
}
.ww-stage {
  position: relative;
  width: 220px;
  height: 220px;
  transform-style: preserve-3d;
}

/* ---------- idle ripples ---------- */
.ring {
  position: absolute;
  left: 50%;
  top: 50%;
  width: 116px;
  height: 116px;
  margin: -58px 0 0 -58px;
  border-radius: 50%;
  border: 1.5px solid rgba(9, 9, 11, 0.18);
  opacity: 0;
  animation: ringPulse 3.6s ease-out infinite;
  pointer-events: none;
}
.ring.r2 { animation-delay: 1.2s; }
.ring.r3 { animation-delay: 2.4s; }
.ww.hover .ring {
  animation-duration: 2.2s;
  border-color: rgba(9, 9, 11, 0.35);
}
@keyframes ringPulse {
  0%   { transform: scale(0.55); opacity: 0.85; }
  100% { transform: scale(1.85); opacity: 0; }
}

/* ---------- orbiting particles ---------- */
.orbit {
  position: absolute;
  left: 50%;
  top: 50%;
  animation: orbitSpin 9s linear infinite;
  pointer-events: none;
}
.orbit.o1 { width: 168px; height: 168px; margin: -84px 0 0 -84px; }
.orbit.o2 { width: 196px; height: 196px; margin: -98px 0 0 -98px; animation-duration: 13s; animation-direction: reverse; }
.orbit.o3 { width: 150px; height: 150px; margin: -75px 0 0 -75px; animation-duration: 7s; }
.orbit .dot {
  position: absolute;
  left: 50%;
  top: -3px;
  width: 6px;
  height: 6px;
  margin-left: -3px;
  border-radius: 50%;
  background: #4f46e5;
  box-shadow: 0 0 10px rgba(79, 70, 229, 0.6);
}
.orbit.o2 .dot { width: 5px; height: 5px; background: #06b6d4; box-shadow: 0 0 8px rgba(6, 182, 212, 0.5); }
.orbit.o3 .dot { width: 4px; height: 4px; background: #8b5cf6; box-shadow: 0 0 8px rgba(139, 92, 246, 0.5); }
.ww.hover .orbit.o1 { animation-duration: 5s; }
.ww.hover .orbit.o2 { animation-duration: 8s; }
.ww.hover .orbit.o3 { animation-duration: 4s; }
@keyframes orbitSpin {
  to { transform: rotate(360deg); }
}

/* ---------- center orb ---------- */
.orb-float { animation: floatBob 3.4s ease-in-out infinite; }
@keyframes floatBob {
  0%, 100% { transform: translateY(-4px); }
  50%      { transform: translateY(4px); }
}

.orb {
  position: relative;
  width: 96px;
  height: 96px;
  margin: 62px auto 0;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(145deg, #1e1b4b 0%, #0f172a 45%, #020617 100%);
  box-shadow:
    0 16px 36px rgba(15, 23, 42, 0.28),
    0 0 0 1px rgba(99, 102, 241, 0.2),
    inset 0 2px 6px rgba(255, 255, 255, 0.25);
  transition: transform 0.18s ease, box-shadow 0.25s ease;
}
.ww.hover .orb {
  box-shadow:
    0 20px 48px rgba(79, 70, 229, 0.28),
    0 0 0 1.5px rgba(99, 102, 241, 0.4),
    inset 0 2px 6px rgba(255, 255, 255, 0.35);
}
.ww.pressed .orb { transform: scale(0.9); }
.ww.popped .orb { animation: orbPop 0.55s cubic-bezier(0.34, 1.56, 0.64, 1); }
@keyframes orbPop {
  0%   { transform: scale(1); }
  35%  { transform: scale(1.16); }
  70%  { transform: scale(0.95); }
  100% { transform: scale(1); }
}
.ww:focus-visible .orb {
  box-shadow:
    0 0 0 6px rgba(9, 9, 11, 0.18),
    0 12px 30px rgba(0, 0, 0, 0.28);
}

.orb-glow {
  position: absolute;
  inset: -7px;
  border-radius: 50%;
  background: radial-gradient(circle at 50% 42%, rgba(255, 255, 255, 0.4), rgba(255, 255, 255, 0) 58%);
  opacity: 0.55;
  animation: glowPulse 2.6s ease-in-out infinite;
}
@keyframes glowPulse {
  0%, 100% { opacity: 0.35; }
  50%      { opacity: 0.8; }
}

.orb-face {
  position: relative;
  color: #fff;
  font-size: 34px;
  font-weight: 700;
  line-height: 1;
  text-shadow: 0 2px 8px rgba(0, 0, 0, 0.3);
}

/* ---------- click ripples ---------- */
.burst {
  position: absolute;
  width: 18px;
  height: 18px;
  margin: -9px 0 0 -9px;
  border-radius: 50%;
  border: 2px solid #09090b;
  opacity: 0;
  animation: burstRing 0.8s ease-out forwards;
  pointer-events: none;
}
.burst:nth-child(2n) { border-color: #52525b; }
@keyframes burstRing {
  0%   { transform: scale(1); opacity: 0.95; }
  100% { transform: scale(9); opacity: 0; }
}

/* ---------- caption ---------- */
.ww-caption {
  margin: 0;
  font-size: 14px;
  line-height: 1.7;
  color: var(--text-muted);
  transition: color 0.2s ease, transform 0.2s ease;
  text-align: center;
}
.ww.hover .ww-caption { color: var(--mono-black); transform: translateY(-1px); }

/* ---------- reduced-motion preference ---------- */
@media (prefers-reduced-motion: reduce) {
  .ring, .orbit, .orb-float, .orb-glow { animation: none !important; }
  .ww-caption { transition: none; }
}
</style>
