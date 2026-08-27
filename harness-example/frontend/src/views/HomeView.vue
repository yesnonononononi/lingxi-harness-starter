<script setup>
import { ref, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { getUser, removeToken } from '../utils/auth'
import request from '../utils/request'
import FileDiff from '../components/FileDiff.vue'
import MarkdownContent from '../components/MarkdownContent.vue'

const router = useRouter()
const user = ref(getUser() || { name: '' })

// ====== 实时事件(SSE) ======
const sseStatus = ref('未连接')
const events = ref([])
const input = ref('')
const inputFocused = ref(false)
const sending = ref(false)
const logRef = ref(null)
const workdir = ref('')
const editingDir = ref(false)
const dirInput = ref('')
const savingDir = ref(false)
let es = null
// 流式打字机期间用纯文本渲染，停顿后切换到 Markdown，避免每个 chunk 都重解析导致卡顿
let mdSettleTimer = null
const MD_SETTLE_MS = 350

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

// Normalize the token usage payload (any field naming style) into { input, output, total }
function parseTokenUsage(tokenUsage) {
  if (!tokenUsage) return null
  const input = tokenUsage.promptTokens ?? tokenUsage.inputTokens ?? tokenUsage.inputTokenCount
  const output = tokenUsage.completionTokens ?? tokenUsage.outputTokens ?? tokenUsage.outputTokenCount
  const total = tokenUsage.totalTokens ?? tokenUsage.totalTokenCount
  if (typeof input !== 'number' && typeof output !== 'number' && typeof total !== 'number') return null
  return { input, output, total }
}

function appendLog(item) {
  const previous = events.value[events.value.length - 1]
  // tool items have their own running/done status; other events are marked loading
  if (previous && previous.type !== 'TOOL_STARTED') previous.loading = false

  const nextItem = { ...item, loading: !terminalEventTypes.has(item.type) }
  if (item.type === 'EXECUTION_COMPLETED') {
    nextItem.tokens = parseTokenUsage(item.tokenUsage)
  }

  events.value.push(nextItem)
  scrollToBottom()
}

function scrollToBottom() {
  nextTick(() => {
    if (logRef.value) {
      logRef.value.scrollTop = logRef.value.scrollHeight
    }
  })
}

// 流式暂停 MD_SETTLE_MS 无新 chunk 后，把仍在打字机的消息切换为 Markdown 渲染
function scheduleMdSettle() {
  if (mdSettleTimer) clearTimeout(mdSettleTimer)
  mdSettleTimer = setTimeout(() => {
    events.value.forEach((e) => {
      if (e.type === 'AGENT_MESSAGE') e.streaming = false
    })
    scrollToBottom()
  }, MD_SETTLE_MS)
}

// 一轮执行结束时立即稳定化该 execution 的消息（等不到 debounce 就切换）
function settleMarkdown(executionId) {
  if (mdSettleTimer) {
    clearTimeout(mdSettleTimer)
    mdSettleTimer = null
  }
  events.value.forEach((e) => {
    if (e.type === 'AGENT_MESSAGE' && (!executionId || e.executionId === executionId)) e.streaming = false
  })
  scrollToBottom()
}

// Find the most recent AGENT_MESSAGE of the given execution, used to accumulate
// streaming PARTIAL_THINKING / PARTIAL_TEXT chunks into a single message bubble.
function findLastAgentMessage(executionId) {
  for (let i = events.value.length - 1; i >= 0; i--) {
    const item = events.value[i]
    if (item.type === 'AGENT_MESSAGE' && (!executionId || item.executionId === executionId)) {
      return item
    }
  }
  return null
}

// Find the most recent in-flight tool call of the given execution to attach its output to.
function findRunningTool(executionId) {
  for (let i = events.value.length - 1; i >= 0; i--) {
    const item = events.value[i]
    if (item.type === 'TOOL_STARTED' && item.status === 'running'
      && (!executionId || item.executionId === executionId)) {
      return item
    }
  }
  return null
}

function toggleTool(item) {
  item.open = !item.open
}

function isReadTool(item) {
  return (item.toolName || '').toLowerCase().includes('read')
}

function prettyArgs(args) {
  if (!args) return '无参数'
  if (typeof args !== 'string') return JSON.stringify(args, null, 2)
  try {
    return JSON.stringify(JSON.parse(args), null, 2)
  } catch {
    return args
  }
}

function connectEvents() {
  // 复用 /api 代理（vite / nginx）转发到后端 /agent/events，避免跨域
  const url = `${location.protocol}//${location.host}/api/agent/events`
  es = new EventSource(url)

  es.onopen = () => {
    sseStatus.value = '已连接'
    appendLog({ time: now(), type: 'SYS', text: 'SSE 已连接，等待 agent 事件...' })
  }
  // EventSource reconnects automatically on transient failures
  es.onerror = () => { sseStatus.value = '重连中...' }
  es.onmessage = (e) => {
    try {
      const evt = JSON.parse(e.data)
      if (evt.type === 'WORKDIR_CHANGED') {
        workdir.value = evt.data?.workdir || workdir.value
        return
      }
      const item = { time: now(), type: evt.type, text: formatEvent(evt), executionId: evt.executionId || '' }
      if (evt.type === 'TOOL_STARTED') {
        item.toolName = evt.data?.toolName || ''
        item.args = evt.data?.args || ''
        item.fileName = extractFileName(evt.data?.args)
        item.fileRange = extractFileRange(evt.data?.args)
        item.status = 'running'
        item.open = false
        item.output = ''
      }
      if (evt.type === 'TOOL_COMPLETED') {
        // attach the output to the most recent in-flight tool call of the same execution;
        // this stays correct even when thinking messages or other events arrive in between
        const tool = findRunningTool(evt.executionId)
        if (tool) {
          tool.output = evt.data?.output || ''
          tool.status = 'done'
          tool.loading = false
        }
        return
      }
      if (evt.type === 'PARTIAL_THINKING' || evt.type === 'PARTIAL_TEXT') {
        // accumulate streaming chunks into the last AGENT_MESSAGE of the same execution
        // (typewriter effect); create the bubble on first chunk if it does not exist yet
        let target = findLastAgentMessage(evt.executionId)
        if (!target) {
          appendLog({ time: now(), type: 'AGENT_MESSAGE', executionId: evt.executionId || '', text: '', thinking: '', streaming: true })
          target = findLastAgentMessage(evt.executionId)
        }
        const chunk = evt.data?.text || ''
        if (evt.type === 'PARTIAL_THINKING') target.thinking = (target.thinking || '') + chunk
        else target.text = (target.text || '') + chunk
        target.streaming = true
        scheduleMdSettle()
        return
      }
      if (evt.type === 'EXECUTION_COMPLETED' || evt.type === 'EXECUTION_FAILED') {
        // a round of execution is done: immediately settle any in-flight markdown
        settleMarkdown(evt.executionId || '')
      }
      if (evt.type === 'FILE_EDIT') {
        // render a Monaco DiffEditor card showing the file change
        appendLog({
          time: now(),
          type: 'FILE_EDIT',
          executionId: evt.executionId || '',
          filePath: evt.data?.filePath || '',
          oldContent: evt.data?.oldContent || '',
          newContent: evt.data?.newContent || '',
          open: false,
        })
        return
      }
      appendLog(item)
    } catch (err) {
      appendLog({ time: now(), type: 'RAW', text: e.data })
    }
  }
}

function extractFileName(args) {
  if (!args) return ''
  let value = args
  if (typeof value === 'string') {
    try { value = JSON.parse(value) } catch { return '' }
  }
  if (!value || typeof value !== 'object') return ''
  const path = value.path || value.filePath || value.file || value.filename
  if (typeof path !== 'string') return ''
  return path.split(/[\\/]/).pop() || ''
}

// Extract the read range (startLine-endLine) for read tools, e.g. "101-300"
function extractFileRange(args) {
  if (!args) return ''
  let value = args
  if (typeof value === 'string') {
    try { value = JSON.parse(value) } catch { return '' }
  }
  if (!value || typeof value !== 'object') return ''
  const start = value.startLine ?? value.start_line
  const end = value.endLine ?? value.end_line
  if (typeof start !== 'number' && typeof end !== 'number') return ''
  if (typeof start === 'number' && typeof end === 'number') return `${start}-${end}`
  return typeof start === 'number' ? `${start}-` : `-${end}`
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
    case 'EXECUTION_COMPLETED':
      return `executionId=${d.executionId || '-'}`
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
    const data = await request.post('/agent/chat', { input: text, streaming: true })
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

// ====== 工作目录 ======
async function loadWorkdir() {
  try {
    const data = await request.get('/agent/workdir')
    workdir.value = data?.workdir || ''
  } catch (e) {
    workdir.value = ''
  }
}

function startEditDir() {
  dirInput.value = workdir.value
  editingDir.value = true
}

function cancelEditDir() {
  editingDir.value = false
}

async function saveWorkdir() {
  const value = dirInput.value.trim()
  if (!value || savingDir.value) return
  savingDir.value = true
  try {
    const data = await request.post('/agent/workdir', { workdir: value })
    workdir.value = data?.workdir || value
    editingDir.value = false
    appendLog({ time: now(), type: 'SYS', text: `工作目录已切换为：${workdir.value}` })
  } catch (e) {
    appendLog({ time: now(), type: 'ERROR', text: `切换工作目录失败：${e?.message || e}` })
  } finally {
    savingDir.value = false
  }
}

onMounted(() => {
  connectEvents()
  loadWorkdir()
})

onBeforeUnmount(() => {
  if (mdSettleTimer) clearTimeout(mdSettleTimer)
  if (es) {
    es.close()
  }
})
</script>

<template>
  <div class="home">
    <header class="topbar">
      <div class="brand-area">
        <div class="logo">灵</div>
        <div class="brand-copy">
          <div class="brand-name">LingXi 灵犀</div>
          <div class="brand-sub">智能协作与实时执行平台</div>
        </div>
      </div>
      <nav class="tabs">
        <a class="tab" href="/official">官网首页</a>
        <a class="tab active" href="/home">工作台</a>
        <a class="tab" href="/login">登录</a>
      </nav>
      <div class="user-area">
        <span class="ws-badge" :class="{ online: sseStatus === '已连接' }">{{ sseStatus }}</span>
        <span class="name">你好，{{ user.name || '访客' }}</span>
        <button class="logout" @click="handleLogout">退出登录</button>
      </div>
    </header>

    <section class="hero-banner" id="features">
      <div class="hero-copy">
        <div class="pill">✨ 灵犀官网主页 · 简洁 / 高级 / 现代</div>
        <h1>构建更聪明的灵犀协作体验</h1>
        <p>灵犀（LingXi）是面向开发者与团队协作的智能能力平台，支持对话式任务编排、实时事件流转与快速接入，让每一次交互都更高效、更顺滑。</p>
      </div>
      <div class="hero-card">
        <div class="hero-metric"><strong>实时</strong><span>SSE 事件流</span></div>
        <div class="hero-metric"><strong>高效</strong><span>异步执行与回调</span></div>
        <div class="hero-metric"><strong>优雅</strong><span>统一视觉与体验</span></div>
      </div>
    </section>

    <main class="chat-wrap">
      <!-- 工作目录栏 -->
      <div class="workdir-bar">
        <span class="workdir-label">工作目录</span>
        <template v-if="!editingDir">
          <span class="workdir-path" :title="workdir">{{ workdir || '未设置' }}</span>
          <button class="workdir-btn" @click="startEditDir">修改</button>
        </template>
        <template v-else>
          <input
            v-model="dirInput"
            class="workdir-input"
            placeholder="请输入新工作目录的绝对路径"
            @keydown.enter="saveWorkdir"
            @keydown.esc="cancelEditDir"
          />
          <button class="workdir-btn primary" :disabled="savingDir" @click="saveWorkdir">{{ savingDir ? '保存中...' : '保存' }}</button>
          <button class="workdir-btn" @click="cancelEditDir">取消</button>
        </template>
      </div>

      <!-- 消息列表 -->
      <div ref="logRef" class="messages">
        <div v-if="events.length === 0" class="empty-state">
          <div class="empty-icon">Hi</div>
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

          <!-- 模型 thinking / 回复 -->
          <div v-else-if="item.type === 'AGENT_MESSAGE'" class="row row-ai">
            <div class="avatar avatar-ai">L</div>
            <div class="ai-body">
              <div class="ai-name">LingXi</div>
              <div v-if="item.thinking" class="bubble bubble-ai thinking-content">
                <div class="thinking-label">Thinking</div>
                <div class="thinking-text">
                  <pre v-if="item.streaming" class="raw-text">{{ item.thinking }}</pre>
                  <MarkdownContent v-else :text="item.thinking" />
                </div>
              </div>
              <div v-if="item.text" class="bubble bubble-ai">
                <pre v-if="item.streaming" class="raw-text">{{ item.text }}</pre>
                <MarkdownContent v-else :text="item.text" />
              </div>
            </div>
          </div>

          <!-- 工具调用卡片：点击头部动态展开/收起，running -> done 状态流转 -->
          <div v-else-if="item.type === 'TOOL_STARTED'" class="tool-event">
            <div class="tool-card" :class="{ open: item.open }">
              <button class="tool-head" :class="{ read: isReadTool(item) }" @click="toggleTool(item)">
                <span class="tool-icon" aria-hidden="true">{{ isReadTool(item) ? '📖' : '🛠' }}</span>
                <span class="tool-name">{{ item.toolName }}</span>
                <span v-if="item.fileName" class="tool-file" :title="item.fileName">{{ item.fileName }}<span v-if="item.fileRange" class="tool-range">{{ item.fileRange }}</span></span>
                <span class="tool-status" :class="item.status">
                  <template v-if="item.status === 'running'">
                    <i></i><i></i><i></i><em>执行中</em>
                  </template>
                  <template v-else>✓ 完成</template>
                </span>
                <span class="tool-chevron" :class="{ rotated: item.open }" aria-hidden="true">▾</span>
              </button>
              <div class="tool-body">
                <div class="tool-body-inner">
                  <div class="tool-section">
                    <div class="tool-section-title">参数</div>
                    <pre class="tool-code">{{ prettyArgs(item.args) }}</pre>
                  </div>
                  <div class="tool-section">
                    <div class="tool-section-title">输出</div>
                    <pre class="tool-code tool-output-text">{{ item.status === 'running' ? '等待工具返回...' : (item.output || '（无输出）') }}</pre>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- 文件编辑 diff 卡片：点击头部展开 Monaco DiffEditor 对比修改前后 -->
          <div v-else-if="item.type === 'FILE_EDIT'" class="tool-event">
            <div class="tool-card diff-card" :class="{ open: item.open }">
              <button class="tool-head" @click="toggleTool(item)">
                <span class="tool-icon" aria-hidden="true">✏️</span>
                <span class="tool-name">文件修改</span>
                <span class="tool-file" :title="item.filePath">{{ item.filePath }}</span>
                <span class="tool-status done">✓ 已应用</span>
                <span class="tool-chevron" :class="{ rotated: item.open }" aria-hidden="true">▾</span>
              </button>
              <div class="tool-body">
                <div class="tool-body-inner">
                  <div class="tool-section diff-section">
                    <div class="tool-section-title">变更对比</div>
                    <FileDiff
                      v-if="item.open"
                      :file-path="item.filePath"
                      :old-content="item.oldContent"
                      :new-content="item.newContent"
                    />
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- 一轮执行完成：token 统计卡片 -->
          <div v-else-if="item.type === 'EXECUTION_COMPLETED'" class="done-card">
            <span class="done-check" aria-hidden="true">✓</span>
            <div class="done-info">
              <span class="done-title">本轮执行完成</span>
              <span v-if="item.executionId" class="done-id">{{ item.executionId }}</span>
            </div>
            <div v-if="item.tokens" class="token-stats">
              <span v-if="typeof item.tokens.input === 'number'" class="token-chip token-input">输入 {{ item.tokens.input }}</span>
              <span v-if="typeof item.tokens.output === 'number'" class="token-chip token-output">输出 {{ item.tokens.output }}</span>
              <span v-if="typeof item.tokens.total === 'number'" class="token-chip token-total">总计 {{ item.tokens.total }}</span>
            </div>
          </div>

          <!-- 其余系统 / 执行 / 错误等事件行（居中小字） -->
          <div v-else class="event-line" :class="item.type.toLowerCase()">
            <span class="ev-tag">{{ eventTypeLabels[item.type] || item.type }}</span>
            <span class="ev-text">{{ item.text }}</span>
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
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
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

/* ====== 顶部栏内部品牌 / 导航 ====== */
.brand-area {
  display: flex;
  align-items: center;
  gap: 12px;
}
.brand-copy {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.brand-name {
  font-size: 15px;
  font-weight: 700;
  color: #262832;
  letter-spacing: 0.3px;
}
.brand-sub {
  font-size: 12px;
  color: #8a8ca0;
}

.tabs {
  display: flex;
  align-items: center;
  gap: 6px;
}
.tab {
  padding: 8px 14px;
  border-radius: 8px;
  color: #55586b;
  font-size: 13px;
  text-decoration: none;
  transition: background 0.2s, color 0.2s;
}
.tab:hover { background: #f5f6fb; }
.tab.active {
  background: #eef2ff;
  color: #4d6bfe;
  font-weight: 600;
}

/* ====== 官网头图 / Hero ====== */
.hero-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 40px;
  padding: 44px 24px;
  max-width: 1100px;
  margin: 0 auto;
  border-bottom: 1px solid #f0f1f5;
}
.hero-copy { flex: 1; min-width: 0; }
.pill {
  display: inline-flex;
  padding: 6px 12px;
  border-radius: 999px;
  background: #eef2ff;
  color: #4d6bfe;
  font-size: 12px;
  font-weight: 600;
}
.hero-copy h1 {
  margin: 16px 0 12px;
  font-size: 34px;
  line-height: 1.2;
  color: #1f2232;
}
.hero-copy p {
  margin: 0;
  font-size: 14px;
  line-height: 1.75;
  color: #55586b;
  max-width: 520px;
}
.hero-card {
  flex-shrink: 0;
  width: 240px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.hero-metric {
  padding: 14px 16px;
  border-radius: 12px;
  background: #f7f8fa;
  border: 1px solid #f0f1f5;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.hero-metric strong {
  font-size: 18px;
  color: #262832;
}
.hero-metric span {
  font-size: 12px;
  color: #8a8ca0;
}

/* ====== 主布局 ====== */
.chat-wrap {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

/* ====== 工作目录栏 ====== */
.workdir-bar {
  flex-shrink: 0;
  width: 100%;
  max-width: 780px;
  margin: 0 auto;
  padding: 14px 0 0;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #8a8ca0;
}
.workdir-label { flex-shrink: 0; font-weight: 600; color: #a0a3b5; }
.workdir-path {
  flex: 1;
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  color: #55586b;
  font-family: ui-monospace, 'SFMono-Regular', Consolas, monospace;
  background: #f7f8fa;
  border: 1px solid #eceef4;
  border-radius: 8px;
  padding: 5px 10px;
}
.workdir-input {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  font-family: ui-monospace, 'SFMono-Regular', Consolas, monospace;
  padding: 5px 10px;
  border: 1px solid #4d6bfe;
  border-radius: 8px;
  outline: none;
  background: #fff;
  color: #262832;
}
.workdir-btn {
  flex-shrink: 0;
  font-size: 12px;
  padding: 5px 12px;
  border-radius: 8px;
  border: 1px solid #e4e6eb;
  background: #fff;
  color: #55586b;
  cursor: pointer;
  transition: all 0.2s;
}
.workdir-btn:hover { border-color: #4d6bfe; color: #4d6bfe; }
.workdir-btn.primary { background: #4d6bfe; border-color: #4d6bfe; color: #fff; }
.workdir-btn.primary:hover:not(:disabled) { background: #3a57e8; }
.workdir-btn.primary:disabled { opacity: 0.6; cursor: not-allowed; }

/* ====== 消息列表 ====== */
.messages {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 24px 16px 16px;
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
/* 流式打字机期间的纯文本渲染（稳定后切换 MarkdownContent） */
.raw-text {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  font-size: inherit;
  line-height: inherit;
}

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

.thinking-content { border-left: 3px solid #c5cadb; }
.thinking-label { margin-bottom: 5px; color: #8a8ca0; font-size: 11px; font-weight: 600; }
.thinking-text { color: #8a8ca0; font-size: 13px; }

/* ====== 工具调用卡片（动态下拉） ====== */
.tool-event { max-width: 780px; margin: 8px auto; }
.tool-card {
  border: 1px solid #eceef4;
  border-radius: 12px;
  background: #fbfbfd;
  overflow: hidden;
  transition: border-color .2s, box-shadow .2s;
}
.tool-card:hover { border-color: #d9ddf0; }
.tool-card.open { border-color: #ccd5f8; box-shadow: 0 6px 18px rgba(77, 107, 254, .07); }

.tool-head {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border: none;
  background: transparent;
  cursor: pointer;
  font-family: inherit;
  text-align: left;
  color: #262832;
}
.tool-head:hover { background: #f5f6fb; }

.tool-icon {
  flex-shrink: 0;
  width: 26px;
  height: 26px;
  border-radius: 8px;
  background: #eef2ff;
  color: #4d6bfe;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
}
.tool-head.read .tool-icon { background: #eef7f3; }

.tool-name {
  flex-shrink: 0;
  font-size: 13px;
  font-weight: 600;
  color: #4d6bfe;
}
.tool-file {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  color: #8a8ca0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.tool-range {
  margin-left: 6px;
  padding: 1px 6px;
  border-radius: 4px;
  background: rgba(77, 107, 254, 0.08);
  color: #4d6bfe;
  font-size: 11px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  flex-shrink: 0;
}

.tool-status {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 999px;
}
.tool-status.running { color: #4d6bfe; background: #eef2ff; }
.tool-status.done { color: #2f855a; background: #eef7f3; }
.tool-status i {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: currentColor;
  animation: event-pulse 1.1s infinite ease-in-out;
}
.tool-status i:nth-child(2) { animation-delay: .15s; }
.tool-status i:nth-child(3) { animation-delay: .3s; }
.tool-status em { font-style: normal; }

.tool-chevron {
  flex-shrink: 0;
  font-size: 12px;
  color: #a0a3b5;
  transition: transform .25s ease;
}
.tool-chevron.rotated { transform: rotate(180deg); color: #4d6bfe; }

/* grid-rows 动画：展开/收起平滑过渡 */
.tool-body {
  display: grid;
  grid-template-rows: 0fr;
  transition: grid-template-rows .28s cubic-bezier(.16, 1, .3, 1);
}
.tool-card.open .tool-body { grid-template-rows: 1fr; }
.tool-body-inner {
  overflow: hidden;
  min-height: 0;
  border-top: 1px solid #eceef4;
  margin: 0 14px;
}
.tool-section { padding: 10px 0; }
.tool-section + .tool-section { border-top: 1px dashed #eceef4; }
.tool-section-title {
  font-size: 11px;
  font-weight: 600;
  color: #a0a3b5;
  margin-bottom: 6px;
}
.tool-code {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font: 12px/1.6 ui-monospace, 'SFMono-Regular', Consolas, monospace;
  color: #55586b;
}
.tool-output-text {
  max-height: 320px;
  overflow: auto;
  padding-right: 4px;
}

/* ====== 一轮执行完成：token 统计卡片 ====== */
.done-card {
  max-width: 780px;
  margin: 20px auto;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border: 1px solid #e6edf5;
  border-radius: 12px;
  background: linear-gradient(135deg, #f7fafc, #f0f5fb);
  animation: done-in .35s ease both;
}
@keyframes done-in {
  from { opacity: 0; transform: translateY(6px); }
  to   { opacity: 1; transform: translateY(0); }
}
.done-check {
  flex-shrink: 0;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: #34d399;
  color: #fff;
  font-size: 13px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}
.done-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.done-title { font-size: 13px; font-weight: 600; color: #262832; }
.done-id {
  font-size: 11px;
  color: #a0a3b5;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.token-stats { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.token-chip {
  font-size: 11px;
  font-weight: 600;
  padding: 4px 10px;
  border-radius: 999px;
  white-space: nowrap;
}
.token-input  { color: #4d6bfe; background: #e8edff; }
.token-output { color: #b45309; background: #fef3c7; }
.token-total  { color: #2f855a; background: #e6f7ef; }
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
