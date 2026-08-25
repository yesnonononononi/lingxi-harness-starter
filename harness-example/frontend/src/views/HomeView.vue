<script setup>
import { ref, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { getUser, removeToken } from '../utils/auth'
import request from '../utils/request'

const router = useRouter()
const user = ref(getUser() || { name: '' })

// ====== 实时事件(WebSocket) ======
const wsStatus = ref('未连接')
const events = ref([])
const input = ref('')
const inputFocused = ref(false)
const sending = ref(false)
const logRef = ref(null)
let ws = null

const eventTypeLabels = {
  SYS: '系统',
  USER: '用户',
  RAW: '原始事件',
  ERROR: '错误',
  EXECUTION_STARTED: '执行',
  TOOL_STARTED: '工具',
  TOOL_COMPLETED: '工具',
  AGENT_MESSAGE: '智能助手',
  EXECUTION_COMPLETED: '完成',
  EXECUTION_FAILED: '失败'
}

const terminalEventTypes = new Set(['EXECUTION_COMPLETED', 'EXECUTION_FAILED'])

function formatTokenUsage(tokenUsage) {
  if (!tokenUsage) return ''
  const prompt = tokenUsage.promptTokens ?? tokenUsage.inputTokens ?? tokenUsage.inputTokenCount
  const completion = tokenUsage.completionTokens ?? tokenUsage.outputTokens ?? tokenUsage.outputTokenCount
  const total = tokenUsage.totalTokens ?? tokenUsage.totalTokenCount

  const parts = []
  if (typeof prompt === 'number') parts.push(`输入 ${prompt}`)
  if (typeof completion === 'number') parts.push(`输出 ${completion}`)
  if (typeof total === 'number') parts.push(`总计 ${total}`)
  return parts.join('，')
}

function isTokenUsagePayload(value) {
  return value && typeof value === 'object' && (
    'promptTokens' in value ||
    'completionTokens' in value ||
    'totalTokens' in value ||
    'inputTokens' in value ||
    'outputTokens' in value ||
    'inputTokenCount' in value ||
    'outputTokenCount' in value ||
    'totalTokenCount' in value
  )
}

function appendLog(item) {
  const previous = events.value[events.value.length - 1]
  if (previous) previous.loading = false

  const nextItem = { ...item, loading: !terminalEventTypes.has(item.type) }
  if (item.type === 'EXECUTION_COMPLETED') {
    nextItem.tokenText = formatTokenUsage(item.tokenUsage)
  }

  events.value.push(nextItem)
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
    appendLog({ time: now(), type: 'SYS', text: 'WebSocket 已连接，等待 agent 事件...' })
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
      return `executionId=${d.executionId || '-'}`
    case 'TOOL_STARTED':
      return d.toolName ? `${d.toolName}` : '工具调用中'
    case 'TOOL_COMPLETED':
      return d.output || '工具返回结果'
    case 'AGENT_MESSAGE':
      return d.text || '(模型思考中...)'
    case 'EXECUTION_COMPLETED': {
      const tokenText = formatTokenUsage(d.tokenUsage)
      return tokenText ? `executionId=${d.executionId || '-'}，${tokenText}` : `executionId=${d.executionId || '-'}`
    }
    case 'EXECUTION_FAILED':
      return d.error ? `${d.error}` : '执行失败'
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
    const data = await request.post('/agent/chat', { input: text })
    appendLog({ time: now(), type: 'SYS', text: `任务已提交：${data?.message || 'ok'}` })
  } catch (e) {
    appendLog({ time: now(), type: 'ERROR', text: `发送失败：${e?.message || e}` })
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

    <main class="chat-wrap">
      <!-- 消息列表 -->
      <div ref="logRef" class="messages">
        <div v-if="events.length === 0" class="empty-state">
          <div class=empty-icon>Hi</div>
          <h2>Nice to meet you!</h2>
          <p>输入一条指令，Agent 将通过工具调用自动完成任务</p>
        </div>

        <template v-for="(item, index) in events" :key="index">
          <!-- 用户消息：右侧蓝色气泡 -->
          <div v-if="item.type === 'USER'" class="row row-user">
            <div class="bubble bubble-user">
              <div class="bubble-text">{{ item.text }}</div>
            </div>
            <div class="avatar avatar-user">{{ (user.name || '客').slice(0, 1) }}</div>
          </div>

          <!-- AI 回复：左侧头像 + 浅色气泡 -->
          <div v-else-if="item.type === 'AGENT_MESSAGE'" class="row row-ai">
            <div class="avatar avatar-ai">L</div>
            <div class="ai-body">
              <div class="ai-name">LingXi</div>
              <div class="bubble bubble-ai">
                <div class="bubble-text">{{ item.text }}</div>
              </div>
            </div>
          </div>

          <!-- 系统 / 工具调用等事件行（居中小字） -->
          <div v-else class="event-line" :class="item.type.toLowerCase()">
            <span class="ev-tag">{{ eventTypeLabels[item.type] || item.type }}</span>
            <span class="ev-text">{{ item.text }}</span>
            <span v-if="item.type === 'EXECUTION_COMPLETED' && item.tokenText" class="ev-token">{{ item.tokenText }}</span>
            <span v-if="item.loading" class="event-loading" aria-label="处理中">
              <i></i><i></i><i></i>
            </span>
          </div>
        </template>

        <!-- 发送中的等待动画 -->
        <div v-if="sending" class="row row-ai">
          <div class="avatar avatar-ai">L</div>
          <div class="ai-body">
            <div class="bubble bubble-ai thinking"><span></span><span></span><span></span></div>
          </div>
        </div>
      </div>

      <!-- 底部输入框 -->
      <div class="input-area">
        <div class="input-box" :class="{ focused: inputFocused }">
          <textarea
            v-model="input"
            rows="1"
            placeholder="给 LingXi 发送消息..."
            :disabled="sending"
            @keydown.enter.exact.prevent="sendMessage"
            @focus="inputFocused = true"
            @blur="inputFocused = false"
          ></textarea>
          <button
            class="send-btn"
            :disabled="sending || !input.trim()"
            @click="sendMessage"
            title="发送"
          >
            ➤
          </button>
        </div>
        <p class="tips">内容由 AI 生成，请仔细甄别</p>
      </div>
    </main>
  </div>
</template>

<style scoped>
* { box-sizing: border-box; }

.home {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: #ffffff;
  color: #262832;
}

/* ====== 顶部栏 ====== */
.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 24px;
  border-bottom: 1px solid #f0f1f5;
  background: #ffffff;
  flex-shrink: 0;
}
.logo { font-size: 17px; font-weight: 700; letter-spacing: 0.5px; color: #4d6bfe; }
.user-area { display: flex; align-items: center; gap: 14px; }
.name { font-size: 14px; color: #55586b; }
.ws-badge {
  font-size: 12px;
  padding: 3px 10px;
  border-radius: 999px;
  color: #f56c6c;
  background: #fef0f0;
}
.ws-badge.online { color: #67c23a; background: #f0f9eb; }
.logout {
  padding: 6px 14px;
  font-size: 13px;
  color: #55586b;
  background: transparent;
  border: 1px solid #e4e6eb;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
}
.logout:hover { border-color: #4d6bfe; color: #4d6bfe; }

/* ====== 主布局 ====== */
.chat-wrap {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

/* ====== 消息列表 ====== */
.messages {
  flex: 1;
  overflow-y: auto;
  padding: 28px 16px 16px;
}
.messages::-webkit-scrollbar { width: 6px; }
.messages::-webkit-scrollbar-thumb { background: #dfe1e8; border-radius: 3px; }

.empty-state {
  text-align: center;
  padding-top: 12vh;
  color: #55586b;
}
.empty-icon {
  width: 64px;
  height: 64px;
  margin: 0 auto 18px;
  border-radius: 18px;
  background: #4d6bfe;
  color: #fff;
  font-size: 22px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}
.empty-state h2 { margin: 0 0 8px; font-size: 20px; color: #262832; }
.empty-state p { margin: 0; font-size: 14px; color: #8a8ca0; }

.row { display: flex; gap: 12px; max-width: 780px; margin: 0 auto 24px; }
.row-user { justify-content: flex-end; }

.avatar {
  flex-shrink: 0;
  width: 34px;
  height: 34px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  font-weight: 600;
}
.avatar-user { background: #4d6bfe; color: #fff; order: 2; }
.avatar-ai { background: #ffffff; border: 1px solid #e4e6eb; color: #4d6bfe; }

.ai-body { max-width: calc(100% - 46px); }
.ai-name { font-size: 13px; color: #8a8ca0; margin-bottom: 6px; }

.bubble {
  padding: 12px 16px;
  border-radius: 12px;
  font-size: 15px;
  line-height: 1.65;
}
.bubble-text { white-space: pre-wrap; word-break: break-word; }

.bubble-user {
  background: #eef2ff;
  color: #262832;
  border-top-right-radius: 4px;
}
.bubble-ai {
  background: #f7f8fa;
  color: #262832;
  border-top-left-radius: 4px;
}

.thinking { display: flex; gap: 5px; align-items: center; padding: 16px; }
.thinking span {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #b6bacb;
  animation: blink 1.2s infinite ease-in-out;
}
.thinking span:nth-child(2) { animation-delay: 0.2s; }
.thinking span:nth-child(3) { animation-delay: 0.4s; }
@keyframes blink {
  0%, 80%, 100% { opacity: 0.25; transform: scale(0.85); }
  40% { opacity: 1; transform: scale(1); }
}

/* 事件行（工具调用 / 系统） */
.event-line {
  max-width: 780px;
  margin: 0 auto 14px;
  font-size: 13px;
  line-height: 1.6;
  display: flex;
  align-items: baseline;
  gap: 10px;
}
.ev-tag {
  flex-shrink: 0;
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 5px;
  background: #f0f1f5;
  color: #8a8ca0;
}
.ev-text { color: #8a8ca0; word-break: break-all; }
.event-line.error .ev-text,
.event-line.error .ev-tag { color: #f56c6c; }
.event-line.tool_started .ev-tag { background: #eef2ff; color: #4d6bfe; }
.event-line.tool_completed .ev-tag { background: #eef7f3; color: #2f855a; }
.event-line.execution_completed .ev-tag { background: #f3f4f6; color: #4b5563; }
.event-line.agent_message { display: none; }
.ev-token {
  flex-shrink: 0;
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 5px;
  background: #f7f8fa;
  color: #4b5563;
}
.event-loading {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  margin-left: 2px;
}
.event-loading i {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: #4d6bfe;
  animation: event-pulse 1.1s infinite ease-in-out;
}
.event-loading i:nth-child(2) { animation-delay: 0.15s; }
.event-loading i:nth-child(3) { animation-delay: 0.3s; }
@keyframes event-pulse {
  0%, 60%, 100% { opacity: 0.3; transform: translateY(0); }
  30% { opacity: 1; transform: translateY(-2px); }
}


/* ====== 输入区 ====== */
.input-area {
  flex-shrink: 0;
  padding: 0 16px 14px;
  background: linear-gradient(to top, #ffffff 70%, rgba(255,255,255,0));
}
.input-box {
  max-width: 780px;
  margin: 0 auto;
  display: flex;
  align-items: flex-end;
  gap: 10px;
  padding: 10px 12px 10px 18px;
  border: 1px solid #e4e6eb;
  border-radius: 16px;
  background: #f7f8fa;
  transition: border-color 0.2s, box-shadow 0.2s;
}
.input-box.focused {
  border-color: #4d6bfe;
  box-shadow: 0 0 0 3px rgba(77, 107, 254, 0.08);
  background: #fff;
}
.input-box textarea {
  flex: 1;
  border: none;
  outline: none;
  resize: none;
  background: transparent;
  font-size: 15px;
  line-height: 1.6;
  color: #262832;
  max-height: 160px;
  padding: 4px 0;
  font-family: inherit;
}
.input-box textarea::placeholder { color: #a8abc0; }
.send-btn {
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  border: none;
  border-radius: 50%;
  background: #4d6bfe;
  color: #fff;
  font-size: 15px;
  cursor: pointer;
  transition: all 0.2s;
}
.send-btn:hover:not(:disabled) { background: #3a57e8; }
.send-btn:disabled { background: #dfe1e8; cursor: not-allowed; }
.tips {
  text-align: center;
  font-size: 12px;
  color: #c0c2cf;
  margin: 10px 0 0;
}
</style>
