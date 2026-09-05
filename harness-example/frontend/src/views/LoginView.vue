<script setup>
import { ref, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { loginApi } from '../api/auth'
import { setToken, setUser } from '../utils/auth'
import CuteBear from '../components/CuteBear.vue'

const router = useRouter()
const route = useRoute()

const form = ref({ username: '', password: '', remember: false })
const loading = ref(false)
const errorMsg = ref('')

// 小熊的当前状态：idle=闲 / watch=盯着用户名看 / cover=捂住眼睛
const bearMode = ref('idle')

function onFocusField(field) {
  bearMode.value = field
}
function onBlurField() {
  bearMode.value = 'idle'
}

const talk = computed(() => {
  if (bearMode.value === 'watch') return '👀 快输吧，我会一直盯着看的！'
  if (bearMode.value === 'cover') return '🙈 密码我就不偷看啦～'
  return '嗨～我是灵犀熊，欢迎回来 (˶ᵔ ᵕ ᵔ˶)'
})

async function handleLogin() {
  errorMsg.value = ''
  if (!form.value.username.trim() || !form.value.password.trim()) {
    errorMsg.value = '请输入账号和密码'
    return
  }

  loading.value = true
  try {
    // 示例：若后端未就绪，可使用本地模拟登录（注释掉真实接口即可）
    // const data = await loginApi({
    //   username: form.value.username,
    //   password: form.value.password,
    // })

    // 本地模拟登录，便于前后端联调前演示路由与拦截器
    const data = { token: `mock-token-${Date.now()}`, user: { name: form.value.username } }

    setToken(data.token)
    setUser(data.user)

    // 跳转到来源页或首页
    const redirect = route.query.redirect || '/home'
    router.push(redirect)
  } catch (e) {
    errorMsg.value = e?.message || '登录失败，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="shell">
      <!-- 左侧：小熊 -->
      <section class="scene">
        <span class="orb orb-a"></span>
        <span class="orb orb-b"></span>
        <span class="orb orb-c"></span>

        <div class="mascot">
          <div class="bear-box">
            <CuteBear :mode="bearMode" />
          </div>

          <div class="speech">
            <span class="dot dot-1"></span>
            <span class="dot dot-2"></span>
            <span class="dot dot-3"></span>
            <p :class="bearMode">{{ talk }}</p>
          </div>
        </div>

        <div class="brand-tag">
          <span class="logo-badge">灵</span>
          <div>
            <h1>LingXi 灵犀</h1>
            <p>连接灵感，驱动未来。</p>
          </div>
        </div>
      </section>

      <!-- 右侧：登录表单 -->
      <section class="panel">
        <div class="login-card">
          <h2>欢迎回来 👋</h2>
          <p class="sub">登录你的账号，继续你的灵感之旅</p>

          <form class="login-form" @submit.prevent="handleLogin">
            <div class="field">
              <label for="username">邮箱 / 用户名</label>
              <input
                id="username"
                v-model.trim="form.username"
                type="text"
                placeholder="请输入邮箱或用户名"
                autocomplete="username"
                @focus="onFocusField('watch')"
                @blur="onBlurField"
              />
            </div>

            <div class="field">
              <label for="password">密码</label>
              <input
                id="password"
                v-model.trim="form.password"
                type="password"
                placeholder="请输入密码"
                autocomplete="current-password"
                @focus="onFocusField('cover')"
                @blur="onBlurField"
              />
            </div>

            <div class="row">
              <label class="check">
                <input v-model="form.remember" type="checkbox" />
                <span class="box"></span>
                <span>记住我</span>
              </label>
              <a class="forgot" href="#">忘记密码？</a>
            </div>

            <p v-if="errorMsg" class="error">{{ errorMsg }}</p>

            <button class="btn" type="submit" :disabled="loading">
              <span v-if="loading" class="spinner"></span>
              {{ loading ? '登录中...' : '登 录' }}
            </button>
          </form>

          <p class="hint">没有账号？<a href="#">立即注册</a> · 输入密码时小熊会捂住眼睛哦 🐻</p>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  box-sizing: border-box;
  overflow-x: hidden;
  overflow-y: auto;
  background:
    radial-gradient(ellipse 60% 50% at 20% 8%, rgba(99, 102, 241, 0.10), transparent 60%),
    radial-gradient(ellipse 55% 45% at 88% 18%, rgba(34, 211, 238, 0.09), transparent 60%),
    radial-gradient(ellipse 60% 55% at 72% 96%, rgba(236, 72, 153, 0.08), transparent 60%),
    linear-gradient(135deg, #ffffff 0%, #f6f7ff 55%, #eef2ff 100%);
}

.shell {
  width: min(1120px, 100%);
  display: flex;
  align-items: stretch;
  gap: 40px;
}

/* ---------- 左侧场景 ---------- */
.scene {
  position: relative;
  flex: 1.15 1 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 26px;
  padding: 12px 0 16px;
}

/* 背景光斑 */
.orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(10px);
  pointer-events: none;
}
.orb-a {
  width: 320px;
  height: 320px;
  left: -60px;
  top: 4%;
  background: radial-gradient(circle, rgba(99, 102, 241, 0.22), transparent 70%);
}
.orb-b {
  width: 260px;
  height: 260px;
  right: -40px;
  top: 42%;
  background: radial-gradient(circle, rgba(34, 211, 238, 0.18), transparent 70%);
}
.orb-c {
  width: 220px;
  height: 220px;
  left: 26%;
  bottom: -30px;
  background: radial-gradient(circle, rgba(244, 114, 182, 0.14), transparent 70%);
}

/* 小熊本体 */
.mascot {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 22px;
}
.bear-box {
  width: clamp(320px, 38vh, 500px);
  aspect-ratio: 400 / 500;
  position: relative;
  z-index: 1;
}
.bear-box :deep(.bear-svg) {
  width: 100%;
  height: 100%;
}

/* 台词气泡 */
.speech {
  position: relative;
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(12px);
  border: 1px solid rgba(99, 102, 241, 0.12);
  border-radius: 16px 16px 16px 4px;
  padding: 10px 16px;
  font-size: 14px;
  color: #334155;
  box-shadow: 0 18px 40px rgba(30, 41, 59, 0.12);
  z-index: 1;
}
.speech p {
  margin: 0;
}
.speech .dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  margin-right: 8px;
  background: #818cf8;
  vertical-align: middle;
}
.speech .dot-2 { background: #22d3ee; }
.speech .dot-3 { background: #e879f9; }

/* 左下角品牌 */
.brand-tag {
  position: absolute;
  left: 6px;
  bottom: 8px;
  display: flex;
  align-items: center;
  gap: 12px;
}
.brand-tag .logo-badge {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  background: linear-gradient(135deg, #818cf8, #22d3ee);
  color: #fff;
  font-size: 21px;
  font-weight: 800;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 10px 22px rgba(99, 102, 241, 0.5);
}
.brand-tag h1 {
  font-size: 18px;
  margin: 0;
  color: #0f172a;
}
.brand-tag p {
  margin: 2px 0 0;
  font-size: 12px;
  color: #64748b;
}

/* ---------- 右侧表单 ---------- */
.panel {
  flex: 0 1 460px;
  display: flex;
  align-items: center;
  justify-content: center;
  min-width: 0;
}
.login-card {
  width: 100%;
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(26px) saturate(150%);
  border: 1px solid rgba(226, 232, 240, 0.9);
  border-radius: 24px;
  padding: 38px 34px 30px;
  box-shadow: 0 30px 70px rgba(79, 70, 229, 0.10), 0 12px 32px rgba(30, 41, 59, 0.10);
}
.login-card h2 { font-size: 24px; margin: 0 0 4px; color: #0f172a; }
.login-card .sub { margin: 0 0 24px; font-size: 13.5px; color: #64748b; }

.field { margin-bottom: 16px; }
.field label { display: block; font-size: 13px; color: #475569; margin-bottom: 8px; }
.field input {
  width: 100%;
  box-sizing: border-box;
  padding: 12px 14px;
  font-size: 15px;
  color: #0f172a;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  outline: none;
  transition: border-color 0.25s, box-shadow 0.25s;
}
.field input:focus {
  border-color: #818cf8;
  box-shadow: 0 0 0 4px rgba(99, 102, 241, 0.14);
}

.row { display: flex; align-items: center; justify-content: space-between; margin: 4px 0 18px; font-size: 13px; }
.check { display: flex; align-items: center; gap: 8px; color: #64748b; cursor: pointer; }
.check input { display: none; }
.check .box {
  width: 16px; height: 16px; border-radius: 5px;
  border: 1px solid #cbd5e1;
  background: #ffffff;
  display: flex; align-items: center; justify-content: center;
}
.check input:checked + .box {
  background: linear-gradient(135deg, #6366f1, #4f46e5);
  border-color: transparent;
}
.check input:checked + .box::after {
  content: '';
  width: 8px; height: 4px;
  border-left: 2px solid #fff;
  border-bottom: 2px solid #fff;
  transform: rotate(-45deg) translateY(-1px);
}
.forgot { color: #6366f1; text-decoration: none; font-weight: 500; }
.forgot:hover { color: #4f46e5; }

.error { color: #f87171; font-size: 13px; margin: 0 0 12px; }

.btn {
  width: 100%;
  padding: 13px;
  border: none;
  border-radius: 12px;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 2px;
  cursor: pointer;
  color: #fff;
  background: linear-gradient(135deg, #6366f1, #4f46e5);
  box-shadow: 0 12px 26px rgba(79, 70, 229, 0.42);
  transition: all 0.3s ease;
}
.btn:hover:not(:disabled) { transform: translateY(-2px); }
.btn:disabled { opacity: 0.8; cursor: not-allowed; }
.spinner {
  display: inline-block;
  width: 14px; height: 14px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-top-color: #fff;
  border-radius: 50%;
  vertical-align: middle;
  margin-right: 8px;
  animation: spin 0.7s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

.hint {
  margin: 18px 0 0;
  text-align: center;
  font-size: 12.5px;
  color: #64748b;
}
.hint a { color: #6366f1; text-decoration: none; }
.hint a:hover { color: #4f46e5; }

/* ---------- 响应式：小屏只保留表单 ---------- */
@media (max-width: 940px) {
  .scene { display: none; }
  .shell { justify-content: center; }
}
@media (max-height: 700px) {
  .bear-box { width: clamp(260px, 34vh, 420px); }
}

/* ===== 深蓝主色覆盖（蓝紫 → 深蓝） ===== */
.login-page {
  background:
    radial-gradient(ellipse 60% 50% at 20% 8%, rgba(29, 78, 216, 0.10), transparent 60%),
    radial-gradient(ellipse 55% 45% at 88% 18%, rgba(34, 211, 238, 0.09), transparent 60%),
    radial-gradient(ellipse 60% 55% at 72% 96%, rgba(236, 72, 153, 0.08), transparent 60%),
    linear-gradient(135deg, #ffffff 0%, #f6f8ff 55%, #eaf0fb 100%);
}
.orb-a { background: radial-gradient(circle, rgba(29, 78, 216, 0.22), transparent 70%); }
.speech { border: 1px solid rgba(29, 78, 216, 0.12); }
.speech .dot { background: #2563eb; }
.brand-tag .logo-badge {
  background: linear-gradient(135deg, #1d4ed8, #2563eb);
  box-shadow: 0 10px 22px rgba(29, 78, 216, 0.42);
}
.login-card {
  box-shadow: 0 30px 70px rgba(30, 64, 175, 0.12), 0 12px 32px rgba(30, 41, 59, 0.10);
}
.field input:focus {
  border-color: #1d4ed8;
  box-shadow: 0 0 0 4px rgba(29, 78, 216, 0.14);
}
.check input:checked + .box {
  background: linear-gradient(135deg, #1d4ed8, #1e40af);
  border-color: transparent;
}
.forgot,
.hint a { color: #1d4ed8; }
.forgot:hover,
.hint a:hover { color: #1e40af; }
.btn {
  background: linear-gradient(135deg, #1d4ed8, #1e40af);
  box-shadow: 0 12px 26px rgba(30, 64, 175, 0.40);
}
</style>
