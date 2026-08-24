<script setup>
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { loginApi } from '../api/auth'
import { setToken, setUser } from '../utils/auth'

const router = useRouter()
const route = useRoute()

const form = ref({ username: '', password: '', remember: false })
const loading = ref(false)
const errorMsg = ref('')

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
    <div class="login-card">
      <div class="brand">
        <div class="logo-badge">灵</div>
        <h1>LingXi 灵犀</h1>
        <p>连接灵感，驱动未来。</p>
      </div>

      <form class="login-form" @submit.prevent="handleLogin">
        <h2>欢迎回来 👋</h2>
        <p class="sub">登录你的账号，继续你的灵感之旅</p>

        <div class="field">
          <label for="username">邮箱 / 用户名</label>
          <input
            id="username"
            v-model.trim="form.username"
            type="text"
            placeholder="请输入邮箱或用户名"
            autocomplete="username"
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
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background:
    radial-gradient(ellipse 60% 50% at 20% 10%, rgba(99, 102, 241, 0.28), transparent 60%),
    radial-gradient(ellipse 55% 45% at 85% 20%, rgba(34, 211, 238, 0.18), transparent 60%),
    radial-gradient(ellipse 60% 55% at 70% 95%, rgba(168, 85, 247, 0.22), transparent 60%),
    linear-gradient(135deg, #0b1020 0%, #141a33 55%, #1a1440 100%);
}

.login-card {
  width: 100%;
  max-width: 420px;
  background: rgba(18, 23, 48, 0.72);
  backdrop-filter: blur(26px) saturate(150%);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 24px;
  padding: 40px 36px 32px;
  box-shadow: 0 40px 90px rgba(0, 0, 0, 0.55);
}

.brand {
  text-align: center;
  margin-bottom: 28px;
}
.logo-badge {
  width: 52px;
  height: 52px;
  margin: 0 auto 12px;
  border-radius: 14px;
  background: linear-gradient(135deg, #818cf8, #22d3ee);
  color: #fff;
  font-size: 24px;
  font-weight: 800;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 10px 26px rgba(99, 102, 241, 0.5);
}
.brand h1 { font-size: 22px; margin: 0 0 4px; color: #f1f5f9; }
.brand p { margin: 0; font-size: 13px; color: #94a3b8; }

.login-form h2 { font-size: 24px; margin: 0 0 4px; color: #f1f5f9; }
.login-form .sub { margin: 0 0 22px; font-size: 13.5px; color: #94a3b8; }

.field { margin-bottom: 16px; }
.field label { display: block; font-size: 13px; color: #cbd5e1; margin-bottom: 8px; }
.field input {
  width: 100%;
  box-sizing: border-box;
  padding: 12px 14px;
  font-size: 15px;
  color: #f1f5f9;
  background: rgba(11, 16, 32, 0.55);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 12px;
  outline: none;
  transition: border-color 0.25s, box-shadow 0.25s;
}
.field input:focus {
  border-color: #818cf8;
  box-shadow: 0 0 0 4px rgba(99, 102, 241, 0.16);
}

.row { display: flex; align-items: center; justify-content: space-between; margin: 4px 0 18px; font-size: 13px; }
.check { display: flex; align-items: center; gap: 8px; color: #94a3b8; cursor: pointer; }
.check input { display: none; }
.check .box {
  width: 16px; height: 16px; border-radius: 5px;
  border: 1px solid rgba(255, 255, 255, 0.25);
  background: rgba(11, 16, 32, 0.5);
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
.forgot { color: #818cf8; text-decoration: none; font-weight: 500; }
.forgot:hover { color: #c7d2fe; }

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
</style>
