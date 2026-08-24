<script setup>
import { ref, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { getUser, removeToken } from '../utils/auth'
import request from '../utils/request'

const router = useRouter()
const user = ref(getUser() || { name: '访客' })

// ====== 实时事件（WebSocket） ======
const wsStatus = ref('未连接')
const events = ref([])
const input = ref('')
const sending = ref(false)
const logRef = ref(null)
let ws = null

function appendLog(item) {
  events.value.push(item)
  nextTick(() => {
    if (logRef.value) {
      logRef.value.scrollTop = logRef.value.scrollHeight
    }
  })
}

function connectWs() {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  const url = `${proto}://${location.host}/ws/agent`
  ws = new WebSocket(url)

  ws.onopen = () => {
    wsStatus.value = '已连接'
    appendLog({ time: now(), type: 'SYS', text: 'WebSocket 已连接，等待 coding agent 事件...' })
  }
  ws.onclose = () => { wsStatus.value = '连接断开' }
  ws.onerror = () => { wsStatus.value = '连接异常' }
  ws.onmessage = (e) => {
    try {
      const evt = JSON.parse(e.data)
      appendLog({ time: now(), type: evt.type, text: formatEvent(evt) })
    } catch (err) {
      appendLog({ time: now(), type: 'RAW', text: e.data })
    }
  }
}

function formatEvent(evt) {
  const d = evt.data || {}
  switch (evt.type) {
    case 'EXECUTION_STARTED':
      return `执行开始，executionId=${d.executionId}`
    case 'TOOL_STARTED':
      return `调用工具：${d.toolName}`
    case 'TOOL_COMPLETED':
      return `工具输出：${d.output}`
    case 'AGENT_MESSAGE':
      return d.text || '(模型思考中，准备调用工具...)'
    case 'EXECUTION_COMPLETED':
      return `执行完成，executionId=${d.executionId}`
    case 'EXECUTION_FAILED':
      return `执行失败：${d.error}`
    default:
      return JSON.stringify(evt)
  }
}

function now() {
  return new Date().toLocaleTimeString()
}

async function sendMessage() {
  const text = input.value.trim()
  if (!text || sending.value) return

  sending.value = true
  appendLog({ time: now(), type: 'USER', text })
  input.value = ''

  try {
    // 触发后端 coding agent，事件将通过 WebSocket 实时推送
    const data = await request.post('/agent/chat', { input: text })
    appendLog({ time: now(), type: 'SYS', text: `已提交任务：${data?.message || 'ok'}` })
  } catch (e) {
    appendLog({ time: now(), type: 'ERROR', text: `请求失败：${e?.message || e}` })
  } finally {
    sending.value = false
  }
}

function handleLogout() {
  removeToken()
  router.push('/login')
}

onMounted(() => {
  connectWs()
})

onBeforeUnmount(() => {
  if (ws) {
    ws.close()
  }
})
</script>

<template>
  <div class="home">
    <header class="topbar">
      <div class="logo">LingXi 灵犀</div>
      <div class="user-area">
        <span class="ws-badge" :class="{ online: wsStatus === '已连接' }">{{ wsStatus }}</span>
        <span class="name">你好，{{ user.name || '访客' }}</span>
        <button class="logout" @click="handleLogout">退出登录</button>
      </div>
    </header>

    <main class="content">
      <h1>Coding Agent</h1>
      <p>输入自然语言指令，Agent 会通过工具链（读文件 / 改文件 / 终端 / 搜索）自动完成任务，过程实时推送。</p>

      <section class="card chat">
        <div ref="logRef" class="log">
          <div
            v-for="(item, index) in events"
            :key="index"
            class="log-line"
            :class="item.type"
          >
            <span class="time">{{ item.time }}</span>
            <span class="badge">{{ item.type }}</span>
            <span class="text">{{ item.text }}</span>
          </div>
          <div v-if="events.length === 0" class="empty">暂无事件，发送一条指令试试吧～</div>
        </div>

        <div class="input-bar">
          <input
            v-model="input"
            type="text"
            placeholder="例如：读取当前目录结构并帮我创建一个 hello.txt"
            @keyup.enter="sendMessage"
            :disabled="sending"
          />
          <button class="btn" @click="sendMessage" :disabled="sending || !input.trim()">
            {{ sending ? '运行中...' : '发送' }}
          </button>
        </div>
      </section>
    </main>
  </div>
</template>

<style scoped>
.home { min-height: 100vh; display: flex; flex-direction: column; background: #0b1020; color: #f1f5f9; }
.topbar {
  display: flex; align-items: center; justify-content: space-between;
  padding: 16px 28px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  background: rgba(18, 23, 48, 0.6);
}
.logo { font-size: 18px; font-weight: 700; letter-spacing: 1px; }
.user-area { display: flex; align-items: center; gap: 14px; }
.name { font-size: 14px; color: #cbd5e1; }
.ws-badge {
  font-size: 12px;
  padding: 4px 10px;
  border-radius: 999px;
  color: #fca5a5;
  background: rgba(248, 113, 113, 0.12);
  border: 1px solid rgba(248, 113, 113, 0.35);
}
.ws-badge.online { color: #86efac; background: rgba(34, 197, 94, 0.12); border-color: rgba(34, 197, 94, 0.35); }
.logout {
  padding: 8px 16px;
  font-size: 13px;
  color: #fff;
  background: rgba(99, 102, 241, 0.2);
  border: 1px solid rgba(129, 140, 248, 0.4);
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.25s;
}
.logout:hover { background: rgba(99, 102, 241, 0.4); }

.content { flex: 1; padding: 48px 28px; max-width: 860px; margin: 0 auto; width: 100%; box-sizing: border-box; }
.content h1 { font-size: 34px; margin: 0 0 12px; }
.content > p { color: #94a3b8; margin: 0 0 28px; }
.card {
  background: rgba(18, 23, 48, 0.7);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 16px;
  padding: 24px;
}
.card h2 { font-size: 18px; margin: 0 0 8px; }
.card p { color: #94a3b8; font-size: 14px; margin: 0 0 16px; }
.btn {
  padding: 10px 18px;
  font-size: 14px;
  font-weight: 600;
  color: #fff;
  background: linear-gradient(135deg, #6366f1, #4f46e5);
  border: none;
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.25s;
}
.btn:hover:not(:disabled) { transform: translateY(-1px); }
.btn:disabled { opacity: 0.6; cursor: not-allowed; }

/* ====== Coding Agent 聊天区 ====== */
.chat { padding: 16px; display: flex; flex-direction: column; gap: 12px; height: 520px; }
.log {
  flex: 1;
  overflow-y: auto;
  padding: 14px;
  border-radius: 12px;
  background: rgba(11, 16, 32, 0.6);
  border: 1px solid rgba(255, 255, 255, 0.08);
}
.log::-webkit-scrollbar { width: 6px; }
.log::-webkit-scrollbar-thumb { background: rgba(129, 140, 248, 0.4); border-radius: 3px; }
.empty { color: #64748b; font-size: 13px; text-align: center; padding: 40px 0; }
.log-line {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 6px 0;
  font-size: 13px;
  line-height: 1.5;
  border-bottom: 1px dashed rgba(255, 255, 255, 0.04);
}
.log-line .time { color: #64748b; flex-shrink: 0; font-size: 12px; }
.log-line .badge {
  flex-shrink: 0;
  font-size: 11px;
  padding: 2px 7px;
  border-radius: 6px;
  background: rgba(100, 116, 139, 0.25);
  color: #cbd5e1;
}
.log-line .text { color: #e2e8f0; white-space: pre-wrap; word-break: break-all; }

.log-line.USER .badge { background: rgba(99, 102, 241, 0.25); color: #c7d2fe; }
.log-line.USER .text { color: #a5b4fc; }
.log-line.SYS .text { color: #94a3b8; }
.log-line.ERROR .badge { background: rgba(248, 113, 113, 0.25); color: #fecaca; }
.log-line.ERROR .text { color: #fca5a5; }
.log-line.AGENT_MESSAGE .text { color: #f1f5f9; }
.log-line.TOOL_STARTED .badge { background: rgba(34, 211, 238, 0.25); color: #a5f3fc; }
.log-line.TOOL_STARTED .text { color: #67e8f9; }
.log-line.TOOL_COMPLETED .badge { background: rgba(251, 191, 36, 0.25); color: #fde68a; }
.log-line.TOOL_COMPLETED .text { color: #fcd34d; }
.log-line.EXECUTION_STARTED .badge { background: rgba(34, 197, 94, 0.25); color: #bbf7d0; }
.log-line.EXECUTION_COMPLETED .badge { background: rgba(34, 197, 94, 0.25); color: #bbf7d0; }
.log-line.EXECUTION_FAILED .badge { background: rgba(248, 113, 113, 0.3); color: #fecaca; }

.input-bar { display: flex; gap: 10px; }
.input-bar input {
  flex: 1;
  padding: 12px 14px;
  font-size: 14px;
  color: #f1f5f9;
  background: rgba(11, 16, 32, 0.55);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 12px;
  outline: none;
  transition: border-color 0.25s, box-shadow 0.25s;
}
.input-bar input:focus { border-color: #818cf8; box-shadow: 0 0 0 4px rgba(99, 102, 241, 0.16); }
</style>
