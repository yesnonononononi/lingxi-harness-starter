<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
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
// ====== 会话管理（后端临时 Map 存储，仅示例） ======
const sessions = ref([])
const currentSessionId = ref('')
const currentSessionName = ref('')
const showSessionRename = ref(false)
const showSessions = ref(true)
const renameInput = ref('')
// 流式打字机期间用纯文本渲染，停顿后切换到 Markdown，避免每个 chunk 都重解析导致卡顿
let mdSettleTimer = null
const MD_SETTLE_MS = 350

// 是否已有真正的对话内容（系统提示类消息不计入，避免刚连接就挤掉居中欢迎页）
const hasChat = computed(() =>
  events.value.some((e) => !['SYS', 'RAW', 'ERROR'].includes(e.type))
)

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

// thinking 卡片默认收起，点击头部展开/收起（与工具卡片交互一致）
function toggleThinking(item) {
  item.thinkingOpen = !item.thinkingOpen
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
      // 只展示当前会话的事件；切换会话后旧会话的延迟事件被忽略
      if (evt.sessionId && currentSessionId.value && evt.sessionId !== currentSessionId.value) return
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
          appendLog({ time: now(), type: 'AGENT_MESSAGE', executionId: evt.executionId || '', text: '', thinking: '', streaming: true, thinkingOpen: false })
          target = findLastAgentMessage(evt.executionId)
        }
        const chunk = evt.data?.text || ''
        if (evt.type === 'PARTIAL_THINKING') target.thinking = (target.thinking || '') + chunk
        else target.text = (target.text || '') + chunk
        target.streaming = true
        scheduleMdSettle()
        return
      }
      if (evt.type === 'AGENT_MESSAGE') {
        // 流式模式下，最终全量消息的文本已经通过 PARTIAL_TEXT 累积到当前气泡里，
        // 直接把气泡收尾即可；否则会额外多出一段重复的末尾消息
        const inflight = findLastAgentMessage(evt.executionId)
        if (inflight && inflight.streaming) {
          inflight.streaming = false
          return
        }
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
    const body = { input: text, streaming: true }
    if (currentSessionId.value) body.sessionId = currentSessionId.value
    if (currentSessionName.value) body.sessionName = currentSessionName.value
    const data = await request.post('/agent/chat', body)
    // 后端分配/确认 sessionId；会话列表以 conversation store 为准，延迟刷新
    if (data?.sessionId) {
      currentSessionId.value = data.sessionId
      currentSessionName.value = data.sessionName || currentSessionName.value || '新会话'
      loadSessions()
    }
    appendLog({ time: now(), type: 'SYS', text: `任务已提交：${data?.message || 'ok'}` })
  } catch (e) {
    appendLog({ time: now(), type: 'ERROR', text: `发送失败：${e?.message || e}` })
  } finally {
    sending.value = false
  }
}

// ====== 会话管理 ======
async function loadSessions() {
  try {
    const data = await request.get('/agent/sessions')
    sessions.value = data?.sessions || []
  } catch (e) {
    sessions.value = []
  }
}

function newSession() {
  currentSessionId.value = ''
  currentSessionName.value = ''
  events.value = []
  showSessionRename.value = false
  appendLog({ time: now(), type: 'SYS', text: '已新建会话，发送消息后将自动创建 sessionId' })
}

function switchSession(session) {
  if (!session.sessionId || session.sessionId === currentSessionId.value) return
  currentSessionId.value = session.sessionId
  currentSessionName.value = session.sessionName || '新会话'
  events.value = []
  showSessionRename.value = false
  appendLog({ time: now(), type: 'SYS', text: `已切换到会话：${currentSessionName.value}` })
}

function startRename() {
  renameInput.value = currentSessionName.value || ''
  showSessionRename.value = true
}

function cancelRename() {
  showSessionRename.value = false
}

async function saveRename() {
  const name = renameInput.value.trim()
  if (!name || !currentSessionId.value) {
    showSessionRename.value = false
    return
  }
  try {
    const data = await request.post('/agent/sessions/rename', {
      sessionId: currentSessionId.value,
      sessionName: name,
    })
    currentSessionName.value = data?.sessionName || name
    loadSessions()
    appendLog({ time: now(), type: 'SYS', text: `会话已重命名为：${currentSessionName.value}` })
  } catch (e) {
    appendLog({ time: now(), type: 'ERROR', text: `重命名失败：${e?.message || e}` })
  } finally {
    showSessionRename.value = false
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
  loadSessions()
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
        <button class="sessions-toggle" :class="{ active: showSessions }" @click="showSessions = !showSessions">
          ☰ 会话（{{ sessions.length }}）
        </button>
        <span class="ws-badge" :class="{ online: sseStatus === '已连接' }">{{ sseStatus }}</span>
        <span class="name">你好，{{ user.name || '访客' }}</span>
        <button class="logout" @click="handleLogout">退出登录</button>
      </div>
    </header>

    <!-- 会话管理面板：顶部栏下方弹出，纵向列表 -->
    <div v-if="showSessions" class="session-panel">
      <button class="new-session-btn" @click="newSession">＋ 新建会话</button>
      <div class="session-list">
        <div
          v-for="s in sessions"
          :key="s.sessionId"
          class="session-item"
          :class="{ active: s.sessionId === currentSessionId }"
          @click="switchSession(s)"
        >
          <span class="session-name">{{ s.sessionName || '新会话' }}</span>
          <span class="session-id">{{ s.sessionId.slice(0, 8) }}</span>
        </div>
        <div v-if="sessions.length === 0" class="session-empty">暂无会话，点击「新建会话」开始</div>
      </div>
    </div>

    <main class="chat-wrap">
      <div class="layout">
        <!-- 聊天面板 -->
        <div class="chat-panel" :class="{ empty: !hasChat }">
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

      <!-- 当前会话标题 + 重命名 -->
      <div class="session-header">
        <template v-if="!showSessionRename">
          <span class="session-title">{{ currentSessionName || '新会话' }}</span>
          <button v-if="currentSessionId" class="rename-btn" @click="startRename" title="重命名会话">✎ 重命名</button>
        </template>
        <template v-else>
          <input v-model="renameInput" class="rename-input" placeholder="输入会话名称" @keydown.enter="saveRename" @keydown.esc="cancelRename" />
          <button class="rename-btn primary" @click="saveRename">保存</button>
          <button class="rename-btn" @click="cancelRename">取消</button>
        </template>
      </div>

      <!-- 无消息时：欢迎语居中展示，输入框随之居于页面中心 -->
      <div v-if="!hasChat" class="empty-hero">
        <div class="empty-state">
          <div class="empty-icon">Hi</div>
          <h2>Nice to meet you!</h2>
          <p>输入一条指令，Agent 将通过工具调用自动完成任务</p>
        </div>
      </div>

      <!-- 消息列表 -->
      <div v-show="hasChat" ref="logRef" class="messages">
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
              <div v-if="item.thinking" class="tool-event thinking-event">
                <div class="tool-card thinking-card" :class="{ open: item.thinkingOpen }">
                  <button class="tool-head" @click="toggleThinking(item)">
                    <span class="tool-icon" aria-hidden="true">💭</span>
                    <span class="tool-name">Thinking</span>
                    <span class="tool-status" :class="item.streaming ? 'running' : 'done'">
                      <template v-if="item.streaming"><i></i><i></i><i></i><em>思考中</em></template>
                      <template v-else>✓ 思考完成</template>
                    </span>
                    <span class="tool-chevron" :class="{ rotated: item.thinkingOpen }" aria-hidden="true">▾</span>
                  </button>
                  <div class="tool-body">
                    <div class="tool-body-inner">
                      <pre v-if="item.streaming" class="raw-text thinking-text">{{ item.thinking }}</pre>
                      <MarkdownContent v-else class="thinking-text" :text="item.thinking" />
                    </div>
                  </div>
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
        </div>
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

/* ====== 主布局 ====== */
.chat-wrap {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}
.home { position: relative; }
.layout { flex: 1; display: flex; min-height: 0; }

/* ====== 会话管理面板（顶部栏下方弹出，纵向列表） ====== */
.session-panel {
  position: absolute;
  top: 60px;
  left: 16px;
  z-index: 60;
  width: 300px;
  max-height: 65vh;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px;
  background: #fff;
  border: 1px solid #e8eaf1;
  border-radius: 14px;
  box-shadow: 0 12px 32px rgba(30, 34, 60, 0.12);
}
.sessions-toggle {
  padding: 6px 12px;
  font-size: 13px;
  color: #55586b;
  background: transparent;
  border: 1px solid #e4e6eb;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
}
.sessions-toggle:hover { border-color: #4d6bfe; color: #4d6bfe; }
.sessions-toggle.active { background: #eef2ff; border-color: #dbe2ff; color: #4d6bfe; font-weight: 600; }
.new-session-btn {
  width: 100%;
  padding: 9px 0;
  border: 1px solid #4d6bfe;
  border-radius: 10px;
  background: #4d6bfe;
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}
.new-session-btn:hover { background: #3a57e8; }
.session-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.session-item {
  padding: 9px 10px;
  border-radius: 10px;
  border: 1px solid transparent;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 2px;
  transition: all 0.2s;
}
.session-item:hover { background: #f0f2f8; }
.session-item.active { background: #eef2ff; border-color: #dbe2ff; }
.session-name {
  font-size: 13px;
  color: #262832;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.session-item.active .session-name { color: #4d6bfe; font-weight: 600; }
.session-id {
  font-size: 11px;
  color: #a0a3b5;
  font-family: ui-monospace, 'SFMono-Regular', Consolas, monospace;
}
.session-empty {
  padding: 20px 8px;
  text-align: center;
  font-size: 12px;
  line-height: 1.8;
  color: #b0b3c4;
}

/* ====== 聊天面板 ====== */
.chat-panel { flex: 1; display: flex; flex-direction: column; min-width: 0; }

/* ====== 会话标题栏 ====== */
.session-header {
  flex-shrink: 0;
  width: 100%;
  max-width: 780px;
  margin: 0 auto;
  padding: 12px 0 0;
  display: flex;
  align-items: center;
  gap: 8px;
}
.session-title {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  font-weight: 600;
  color: #262832;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.rename-btn {
  flex-shrink: 0;
  font-size: 12px;
  padding: 3px 10px;
  border-radius: 8px;
  border: 1px solid #e4e6eb;
  background: #fff;
  color: #55586b;
  cursor: pointer;
  transition: all 0.2s;
}
.rename-btn:hover { border-color: #4d6bfe; color: #4d6bfe; }
.rename-btn.primary { background: #4d6bfe; border-color: #4d6bfe; color: #fff; }
.rename-btn.primary:hover { background: #3a57e8; }
.rename-input {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  padding: 4px 10px;
  border: 1px solid #4d6bfe;
  border-radius: 8px;
  outline: none;
  color: #262832;
}

/* ====== 工作目录栏 ====== */
.workdir-bar {
  flex-shrink: 0;
  width: 100%;
  max-width: 780px;
  margin: 0 auto;
  padding: 8px 0 0;
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

/* 无消息时：欢迎语 + 输入框整体居中 */
.empty-hero {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  padding: 24px 16px 48px;
}
.chat-panel.empty .messages { display: none; }
.empty-state {
  text-align: center;
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

/* thinking 折叠卡片（与工具卡片同构，默认收起） */
.thinking-event { margin: 4px 0; }
.thinking-card .tool-name { color: #8a8ca0; }
.thinking-text { color: #8a8ca0; font-size: 13px; white-space: pre-wrap; word-break: break-word; }

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

/* ====== 导入代码按钮 ====== */
.import-btn {
  padding: 6px 14px;
  font-size: 13px;
  color: #fff;
  background: #4d6bfe;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
}
.import-btn:hover { background: #3a57e8; }

/* ====== 添加代码弹窗 ====== */
.modal-mask {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: rgba(15, 18, 35, 0.55);
  backdrop-filter: blur(3px);
  animation: modal-fade .2s ease both;
}
@keyframes modal-fade {
  from { opacity: 0; }
  to { opacity: 1; }
}
.modal {
  width: 100%;
  max-width: 640px;
  max-height: 88vh;
  display: flex;
  flex-direction: column;
  background: #fff;
  border-radius: 16px;
  box-shadow: 0 30px 70px rgba(0, 0, 0, 0.35);
  animation: modal-rise .28s cubic-bezier(.16, 1, .3, 1) both;
  overflow: hidden;
}
@keyframes modal-rise {
  from { opacity: 0; transform: translateY(20px) scale(.98); }
  to { opacity: 1; transform: translateY(0) scale(1); }
}
.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid #f0f1f5;
}
.modal-header h3 {
  margin: 0;
  font-size: 16px;
  color: #1f2232;
}
.modal-close {
  border: none;
  background: transparent;
  color: #a0a3b5;
  font-size: 15px;
  cursor: pointer;
  padding: 4px;
  line-height: 1;
  border-radius: 6px;
  transition: all .2s;
}
.modal-close:hover { color: #262832; background: #f5f6fb; }
.modal-body {
  padding: 18px 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  overflow-y: auto;
}
.field { display: flex; flex-direction: column; gap: 6px; }
.field-label {
  font-size: 13px;
  font-weight: 600;
  color: #262832;
}
.field-label em {
  font-style: normal;
  font-weight: 400;
  color: #a0a3b5;
  font-size: 12px;
}
.field-input {
  padding: 9px 12px;
  border: 1px solid #e4e6eb;
  border-radius: 8px;
  font-size: 13px;
  color: #262832;
  outline: none;
  background: #fafbfc;
  transition: border-color .2s, box-shadow .2s;
}
.field-input:focus {
  border-color: #4d6bfe;
  box-shadow: 0 0 0 3px rgba(77, 107, 254, 0.08);
  background: #fff;
}
.field-textarea {
  padding: 10px 12px;
  border: 1px solid #e4e6eb;
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.6;
  color: #262832;
  outline: none;
  resize: vertical;
  min-height: 160px;
  font-family: ui-monospace, 'SFMono-Regular', Consolas, monospace;
  background: #fafbfc;
  transition: border-color .2s, box-shadow .2s;
}
.field-textarea:focus {
  border-color: #4d6bfe;
  box-shadow: 0 0 0 3px rgba(77, 107, 254, 0.08);
  background: #fff;
}
.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 14px 20px;
  border-top: 1px solid #f0f1f5;
  background: #fafbfc;
}
.btn-cancel {
  padding: 8px 18px;
  font-size: 13px;
  color: #55586b;
  background: #fff;
  border: 1px solid #e4e6eb;
  border-radius: 8px;
  cursor: pointer;
  transition: all .2s;
}
.btn-cancel:hover { border-color: #c9ccd6; color: #262832; }
.btn-submit {
  padding: 8px 22px;
  font-size: 13px;
  font-weight: 600;
  color: #fff;
  background: #4d6bfe;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  transition: all .2s;
}
.btn-submit:hover:not(:disabled) { background: #3a57e8; }
.btn-submit:disabled { background: #dfe1e8; cursor: not-allowed; }
</style>














