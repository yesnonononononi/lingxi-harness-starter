<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { getUser, removeToken } from '../utils/auth'
import request from '../utils/request'
import { acceptEdit, rejectEdit, acceptTurn, rejectTurn, listPendingEdits } from '../api/fileEdit'
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
// ====== 执行控制（暂停/继续/停止，作用于当前会话） ======
const executing = ref(false)
const paused = ref(false)
const ctlBusy = ref(false)
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
  EXECUTION_FAILED: '失败',
  EXECUTION_CANCELLED: '取消'
}

const terminalEventTypes = new Set(['EXECUTION_COMPLETED', 'EXECUTION_FAILED', 'EXECUTION_CANCELLED'])

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

// Find the most recent *open* AGENT_MESSAGE of the given execution, used to accumulate
// streaming PARTIAL_THINKING / PARTIAL_TEXT chunks into a single message bubble.
// A message body that was already finalized (closed=true, i.e. its round ended and the
// thinking was completed) is never reused: a new think arriving afterwards must start a
// fresh message body instead.
function findLastAgentMessage(executionId) {
  for (let i = events.value.length - 1; i >= 0; i--) {
    const item = events.value[i]
    if (item.type === 'AGENT_MESSAGE' && !item.closed && (!executionId || item.executionId === executionId)) {
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

// 从路径取文件名(下拉框 item 展示用)
function fileNameOf(path) {
  return (path || '').split(/[\\/]/).pop() || path || ''
}

// ====== 文件编辑决策(保留 / 撤销) ======
// 所有仍待裁决的文件编辑(跨轮),输入框上方下拉框的数据源
const showPendingEdits = ref(true)
const pendingEdits = computed(() =>
  events.value.filter((e) => e.type === 'FILE_EDIT' && e.decision === 'pending')
)

// 每轮执行(executionId == turnId)中仍待裁决的编辑数
function pendingEditsOfTurn(executionId) {
  return pendingEdits.value.filter((e) => e.turnId === executionId)
}

async function decideEdit(item, accept) {
  if (!item.recordId || item.decision !== 'pending') return
  item.deciding = true
  try {
    if (accept) await acceptEdit(currentSessionId.value, item.recordId)
    else await rejectEdit(currentSessionId.value, item.recordId)
    item.decision = accept ? 'ACCEPTED' : 'REJECTED'
  } catch (err) {
    appendLog({ time: now(), type: 'ERROR', text: `文件决策失败:${err.message || err}` })
  } finally {
    item.deciding = false
  }
}

async function decideTurn(executionId, accept) {
  if (!pendingEditsOfTurn(executionId).length) return
  try {
    if (accept) await acceptTurn(currentSessionId.value, executionId)
    else await rejectTurn(currentSessionId.value, executionId)
  } catch (err) {
    appendLog({ time: now(), type: 'ERROR', text: `整轮决策失败:${err.message || err}` })
  }
}

// 对当前会话所有待裁决编辑统一决策:按轮分组,逐轮调用整轮接口
async function decideAllPending(accept) {
  const turnIds = [...new Set(pendingEdits.value.map((e) => e.turnId).filter(Boolean))]
  if (!turnIds.length) return
  try {
    for (const turnId of turnIds) {
      if (accept) await acceptTurn(currentSessionId.value, turnId)
      else await rejectTurn(currentSessionId.value, turnId)
    }
  } catch (err) {
    appendLog({ time: now(), type: 'ERROR', text: `统一决策失败:${err.message || err}` })
  }
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
      // 跟踪当前会话执行状态：STARTED -> 运行中，结束事件 -> 空闲
      if (evt.type === 'EXECUTION_STARTED') {
        executing.value = true
        paused.value = false
      } else if (terminalEventTypes.has(evt.type)) {
        executing.value = false
        paused.value = false
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
          appendLog({ time: now(), type: 'AGENT_MESSAGE', executionId: evt.executionId || '', text: '', thinking: '', streaming: true, thinkingOpen: false, closed: false })
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
        // 流式模式下，该轮完整消息的 thinking/text 已通过 PARTIAL_THINKING / PARTIAL_TEXT
        // 累积进当前消息体，这里只负责收尾：把消息体标记为 closed（thinking 完成）。
        // 收尾之后若再来新的 think/text（例如 agent 下一轮推理），将另起新的消息体，
        // 不再向这个已完成的旧消息体追加。
        const inflight = findLastAgentMessage(evt.executionId)
        if (inflight) {
          inflight.streaming = false
          inflight.closed = true
          return
        }
        // 非流式（无 PARTIAL 事件）的完整消息：直接作为独立消息体展示并标记已收尾
        item.closed = true
      }
      if (evt.type === 'EXECUTION_COMPLETED' || evt.type === 'EXECUTION_FAILED' || evt.type === 'EXECUTION_CANCELLED') {
        // a round of execution is done: immediately settle any in-flight markdown
        settleMarkdown(evt.executionId || '')
      }
      if (evt.type === 'FILE_EDIT') {
        // render a Monaco DiffEditor card showing the file change;
        // edits are already applied on disk, recordId enables accept/reject
        appendLog({
          time: now(),
          type: 'FILE_EDIT',
          executionId: evt.executionId || '',
          recordId: evt.data?.recordId || '',
          turnId: evt.data?.turnId || '',
          filePath: evt.data?.filePath || '',
          oldContent: evt.data?.oldContent || '',
          newContent: evt.data?.newContent || '',
          plusLines: evt.data?.plusLines ?? 0,
          minusLines: evt.data?.minusLines ?? 0,
          decision: evt.data?.recordId ? 'pending' : 'none',
          deciding: false,
          open: false,
        })
        return
      }
      if (evt.type === 'FILE_EDIT_DECISION') {
        // any page performed an accept/reject (single edit or whole turn):
        // refresh the decision state of the matching diff cards in place
        const { recordId, turnId, decision } = evt.data || {}
        events.value.forEach((item) => {
          if (item.type !== 'FILE_EDIT' || item.decision === 'none') return
          const hit = recordId ? item.recordId === recordId : (turnId && item.turnId === turnId)
          if (hit) item.decision = decision || item.decision
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
    case 'EXECUTION_CANCELLED':
      return '已取消'
    default:
      return JSON.stringify(evt)
  }
}

function now() {
  return new Date().toLocaleTimeString()
}

async function sendMessage() {
  const text = input.value.trim()
  // 执行/暂停期间发送位被“暂停/继续”占用，Enter 也不应触发新任务
  if (!text || sending.value || executing.value || paused.value) return

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

// ====== 执行控制 ======
async function controlAgent(action) {
  if (!currentSessionId.value || ctlBusy.value) return
  ctlBusy.value = true
  try {
    const data = await request.post(`/agent/${action}`, null, {
      params: { sessionId: currentSessionId.value },
    })
    const applied = !!data?.applied
    appendLog({
      time: now(),
      type: 'SYS',
      text: `已请求${actionLabel(action)}${applied ? '' : '（当前无运行中的执行，已忽略）'}`,
    })
    if (!applied) {
      executing.value = false
      paused.value = false
      return
    }
    if (action === 'pause') paused.value = true
    else if (action === 'resume') paused.value = false
    else if (action === 'stop') {
      // stop 在后端立即取消后台任务；EXECUTION_CANCELLED 事件随后会把状态归零
      paused.value = false
      executing.value = false
    }
  } catch (e) {
    // 404：该会话此刻没有运行中的执行；其它错误原样提示
    executing.value = false
    paused.value = false
    appendLog({ time: now(), type: 'ERROR', text: `${actionLabel(action)}失败：${e?.message || e}` })
  } finally {
    ctlBusy.value = false
  }
}

function actionLabel(action) {
  return { pause: '暂停', resume: '恢复', stop: '停止' }[action] || action
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
  executing.value = false
  paused.value = false
  showSessionRename.value = false
  appendLog({ time: now(), type: 'SYS', text: '已新建会话，发送消息后将自动创建 sessionId' })
}

async function switchSession(session) {
  if (!session.sessionId || session.sessionId === currentSessionId.value) return
  currentSessionId.value = session.sessionId
  currentSessionName.value = session.sessionName || session.sessionId.slice(0, 8)
  events.value = []
  // 控制按钮只作用于当前会话：切换后重置执行状态
  executing.value = false
  paused.value = false
  showSessionRename.value = false
  try {
    // 拉取该会话的历史消息，映射为与实时事件相同的气泡模型
    const data = await request.get(`/agent/sessions/${session.sessionId}/messages`)
    for (const m of data?.messages || []) {
      if (m.role === 'USER') {
        events.value.push({ time: '', type: 'USER', text: m.text || '' })
      } else if (m.role === 'AI') {
        events.value.push({
          time: '', type: 'AGENT_MESSAGE', executionId: '',
          text: m.text || '', thinking: m.thinking || '',
          streaming: false, thinkingOpen: false, closed: true,
        })
      } else if (m.role === 'TOOL') {
        events.value.push({
          time: '', type: 'TOOL_STARTED', executionId: '',
          toolName: m.toolName || 'tool', args: '',
          status: 'done', open: false, output: m.text || '',
        })
      }
    }
  } catch (e) {
    appendLog({ time: now(), type: 'ERROR', text: `加载历史消息失败：${e?.message || e}` })
  }
    // 拉取该会话仍待裁决的编辑,映射为 FILE_EDIT 卡片(无旧/新内容,仅展示与决策)
    try {
      const data = await listPendingEdits(session.sessionId)
      for (const e of (data?.edits || []).slice().reverse()) {
        events.value.push({
          time: '', type: 'FILE_EDIT', executionId: e.turnId || '',
          recordId: e.recordId, turnId: e.turnId,
          filePath: e.filePath, oldContent: '', newContent: '',
          plusLines: e.plusLines ?? 0, minusLines: e.minusLines ?? 0,
          decision: 'pending', deciding: false, open: false,
        })
      }
    } catch (err) {
      console.warn('加载待裁决编辑失败', err)
    }
    appendLog({ time: now(), type: 'SYS', text: `已切换到会话：${currentSessionName.value}` })
  scrollToBottom()
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
    <!-- 左侧边栏：品牌 + 新建会话(sticky 吸顶) + 会话列表 + 左下角状态卡片（DeepSeek 式布局） -->
    <aside class="sidebar" :class="{ collapsed: !showSessions }">
      <!-- 品牌区 -->
      <div class="sidebar-brand">
        <div class="logo">灵</div>
        <div class="brand-copy">
          <div class="brand-name">LingXi 灵犀</div>
          <div class="brand-sub">智能协作 · 实时执行</div>
        </div>
        <button class="brand-collapse" title="收起侧边栏" @click="showSessions = false">‹</button>
      </div>

      <!-- 会话区（内部可滚动），新建会话吸顶常驻 -->
      <div class="session-scroll">
        <div class="new-session-sticky">
          <button class="new-session-btn" @click="newSession">
            <span class="ns-icon" aria-hidden="true">✚</span>
            <span>新建会话</span>
          </button>
        </div>
        <div class="session-list">
          <div
            v-for="s in sessions"
            :key="s.sessionId"
            class="session-item"
            :class="{ active: s.sessionId === currentSessionId }"
            @click="switchSession(s)"
            :title="s.sessionName || s.sessionId"
          >
            <span class="session-icon" aria-hidden="true">💬</span>
            <span class="session-name">{{ s.sessionName || s.sessionId.slice(0, 8) }}</span>
          </div>
          <div v-if="sessions.length === 0" class="session-empty">
            <span class="se-icon" aria-hidden="true">✨</span>
            <span>暂无会话<br />点击上方「新建会话」开始</span>
          </div>
        </div>
      </div>

      <!-- 左下角状态卡片：连接状态 / 会话数 / 用户 / 操作 -->
      <div class="sidebar-foot">
        <div class="foot-box">
          <div class="foot-row foot-status">
            <span class="foot-dot" :class="{ online: sseStatus === '已连接' }"></span>
            <span class="foot-status-text">链接：{{ sseStatus }}</span>
            <span class="foot-count">{{ sessions.length }} 个会话</span>
          </div>
          <div class="foot-row foot-user">
            <span class="foot-avatar">{{ (user.name || '客').slice(0, 1) }}</span>
            <span class="foot-name">{{ user.name || '访客' }}</span>
            <button class="foot-btn mini" :title="'收起侧边栏'" @click="showSessions = false">‹</button>
          </div>
          <div class="foot-actions">
            <button class="foot-btn logout-danger" @click="handleLogout">退出登录</button>
          </div>
        </div>
      </div>
    </aside>

    <!-- 侧栏收起后的悬浮展开按钮 -->
    <button v-if="!showSessions" class="sidebar-expand" title="展开侧边栏" @click="showSessions = true">☰</button>

    <main class="chat-wrap">
      <div class="layout">
        <!-- 聊天面板 -->
        <div class="chat-panel" :class="{ empty: !hasChat }">
      <!-- 当前会话标题 + 重命名（置顶，仿 DeepSeek 顶部会话头） -->
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

          <!-- 文件编辑 diff 卡片:点击头部展开 Monaco DiffEditor 对比修改前后;已应用待裁决 -->
          <div v-else-if="item.type === 'FILE_EDIT'" class="tool-event">
            <div class="tool-card diff-card" :class="{ open: item.open }">
              <button class="tool-head" @click="toggleTool(item)">
                <span class="tool-icon" aria-hidden="true">✏️</span>
                <span class="tool-name">文件修改</span>
                <span class="tool-file" :title="item.filePath">{{ item.filePath }}</span>
                <span class="tool-status done">
                  {{ item.decision === 'REJECTED' ? '↩ 已撤销' : item.decision === 'ACCEPTED' ? '✓ 已保留' : '✓ 已应用' }}
                </span>
                <span class="tool-chevron" :class="{ rotated: item.open }" aria-hidden="true">▾</span>
              </button>
              <div class="tool-body">
                <div class="tool-body-inner">
                  <div class="tool-section diff-section">
                    <div class="tool-section-title">变更对比</div>
                    <FileDiff
                      v-if="item.open"
                      :file-path="item.filePath"
                      :old-content="item.decision === 'REJECTED' ? item.newContent : item.oldContent"
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

          <!-- 一轮执行被取消（外部 stop / 中断） -->
          <div v-else-if="item.type === 'EXECUTION_CANCELLED'" class="done-card cancelled">
            <span class="done-check" aria-hidden="true">✕</span>
            <div class="done-info">
              <span class="done-title">本轮执行已取消</span>
              <span v-if="item.executionId" class="done-id">{{ item.executionId }}</span>
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

      <!-- 待裁决文件编辑下拉框:当前会话所有 PENDING 的 FileRecord -->
      <div v-if="pendingEdits.length" class="pending-edits">
        <button class="pending-head" @click="showPendingEdits = !showPendingEdits">
          <span class="pending-dot" aria-hidden="true"></span>
          <span class="pending-title">{{ pendingEdits.length }} 个文件修改待裁决</span>
          <span class="pending-hint">已写入文件,请选择保留或撤销</span>
          <span class="pending-chevron" :class="{ rotated: showPendingEdits }" aria-hidden="true">▾</span>
        </button>
        <div v-if="showPendingEdits" class="pending-list">
          <div v-for="item in pendingEdits" :key="item.recordId" class="pending-item">
            <span class="pending-file" :title="item.filePath">{{ fileNameOf(item.filePath) }}</span>
            <span class="pending-lines">
              <em class="plus">+{{ item.plusLines ?? 0 }}</em>
              <em class="minus">-{{ item.minusLines ?? 0 }}</em>
            </span>
            <div class="decision-actions">
              <button class="decision-btn keep" :disabled="item.deciding" @click="decideEdit(item, true)">保留</button>
              <button class="decision-btn undo" :disabled="item.deciding" @click="decideEdit(item, false)">撤销</button>
            </div>
          </div>
          <div class="pending-footer">
            <button class="decision-btn keep" @click="decideAllPending(true)">全部保留</button>
            <button class="decision-btn undo" @click="decideAllPending(false)">全部撤销</button>
          </div>
        </div>
      </div>

      <!-- 执行控制条：当前会话有运行/暂停中的 agent 执行时显示（暂停/继续已移入输入框发送位） -->
      <div v-if="executing || paused" class="run-controls">
        <span class="run-state" :class="{ paused }">
          <i></i>{{ paused ? '已暂停，等待继续' : '执行中' }}
        </span>
        <button class="ctl-btn stop" :disabled="ctlBusy" @click="controlAgent('stop')">停止</button>
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
            v-if="executing || paused"
            class="input-ctl-btn"
            :class="paused ? 'resume' : 'pause'"
            :disabled="ctlBusy"
            :title="paused ? '继续执行' : '暂停执行'"
            @click="controlAgent(paused ? 'resume' : 'pause')"
          >
            {{ paused ? '继续' : '暂停' }}
          </button>
          <button
            v-else
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
  /* 固定为视口高度:页面本身不滚动,消息列表在固定窗口内滚动 */
  height: 100vh;
  display: flex;
  overflow: hidden;
  background: #ffffff;
  color: #262832;
}

/* ====== 左侧边栏（DeepSeek 式） ====== */
.sidebar {
  position: relative;
  z-index: 20;
  flex: 0 0 280px;
  width: 280px;
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  background: #f7f7fa;
  border-right: 1px solid #ececf1;
  transition: margin-left 0.26s cubic-bezier(.4, 0, .2, 1);
}
.sidebar.collapsed { margin-left: -280px; }

/* 品牌区 */
.sidebar-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px 16px 10px;
  flex-shrink: 0;
}
.logo {
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  border-radius: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #4d6bfe, #7a5cff);
  color: #fff;
  font-size: 18px;
  font-weight: 700;
  letter-spacing: .5px;
  box-shadow: 0 4px 10px rgba(77, 107, 254, .28);
}
.brand-copy {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1;
  min-width: 0;
}
.brand-name {
  font-size: 15px;
  font-weight: 700;
  color: #1f2232;
  letter-spacing: .3px;
}
.brand-sub {
  font-size: 11px;
  color: #9a9db0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.brand-collapse {
  flex-shrink: 0;
  width: 24px;
  height: 24px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: #a2a5b8;
  font-size: 17px;
  line-height: 1;
  cursor: pointer;
  transition: all .2s;
}
.brand-collapse:hover { background: #ececf2; color: #4d6bfe; }

/* 会话列表滚动区 */
.session-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  padding: 0 12px;
}
.session-scroll::-webkit-scrollbar { width: 6px; }
.session-scroll::-webkit-scrollbar-thumb { background: #dfe1ea; border-radius: 3px; }
.session-scroll::-webkit-scrollbar-thumb:hover { background: #cfd1dd; }

/* 新建会话：吸顶（会话列表滚动时按钮始终留在顶部） */
.new-session-sticky {
  position: sticky;
  top: 0;
  z-index: 5;
  padding: 8px 0 10px;
  background: linear-gradient(#f7f7fa 82%, rgba(247, 247, 250, 0));
}
.new-session-btn {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 11px 0;
  border: none;
  border-radius: 10px;
  background: linear-gradient(135deg, #4d6bfe, #6a5cff);
  color: #fff;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  box-shadow: 0 6px 14px rgba(77, 107, 254, .22);
  transition: all .2s;
}
.new-session-btn:hover {
  filter: brightness(1.06);
  transform: translateY(-1px);
  box-shadow: 0 8px 18px rgba(77, 107, 254, .3);
}
.new-session-btn:active { transform: translateY(0); }
.ns-icon { font-size: 15px; font-weight: 400; }

/* 会话列表 */
.session-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding-bottom: 12px;
}
.session-item {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 10px 10px;
  border-radius: 10px;
  border: 1px solid transparent;
  cursor: pointer;
  transition: all .18s;
}
.session-item:hover { background: #edeef5; }
.session-item.active {
  background: #ffffff;
  border-color: #e6e8f1;
  box-shadow: 0 2px 8px rgba(30, 34, 60, .06);
}
.session-icon { flex-shrink: 0; font-size: 13px; opacity: .8; }
.session-name {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  color: #3a3d50;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.session-item.active .session-name { color: #4d6bfe; font-weight: 600; }
.session-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 30px 8px;
  text-align: center;
  font-size: 12px;
  line-height: 1.7;
  color: #b3b5c6;
}
.se-icon { font-size: 22px; opacity: .55; }

/* ====== 左下角状态卡片 ====== */
.sidebar-foot {
  flex-shrink: 0;
  padding: 8px 12px 14px;
}
.foot-box {
  display: flex;
  flex-direction: column;
  gap: 9px;
  padding: 10px 12px;
  border: 1px solid #e7e8ee;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: 0 4px 14px rgba(30, 34, 60, .05);
}
.foot-row { display: flex; align-items: center; gap: 7px; min-width: 0; }
.foot-status { font-size: 12px; color: #7d8096; }
.foot-dot {
  flex-shrink: 0;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #f87171;
  box-shadow: 0 0 0 3px rgba(248, 113, 113, .14);
}
.foot-dot.online {
  background: #22c55e;
  box-shadow: 0 0 0 3px rgba(34, 197, 94, .15);
}
.foot-status-text { flex-shrink: 0; }
.foot-count {
  flex: 1;
  min-width: 0;
  text-align: right;
  color: #a2a5b8;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.foot-avatar {
  flex-shrink: 0;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #4d6bfe, #6a5cff);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
}
.foot-name {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  font-weight: 600;
  color: #262832;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.foot-btn {
  padding: 5px 12px;
  font-size: 12px;
  color: #5f6378;
  background: #f7f7fa;
  border: 1px solid #e7e8ee;
  border-radius: 8px;
  cursor: pointer;
  transition: all .2s;
}
.foot-btn:hover { border-color: #cfd3e6; color: #4d6bfe; background: #f0f2ff; }
.foot-btn.mini {
  flex-shrink: 0;
  width: 24px;
  padding: 2px 0;
  border: none;
  background: transparent;
  color: #a2a5b8;
  font-size: 16px;
}
.foot-btn.mini:hover { background: #ececf2; color: #4d6bfe; }
.foot-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  border-top: 1px solid #f1f2f6;
  padding-top: 8px;
}
.foot-actions .foot-btn { background: #fff; }
.foot-btn.logout-danger:hover {
  color: #e5533d;
  border-color: #f3c6bd;
  background: #fdf3f1;
}

/* ====== 侧栏收起后的悬浮展开按钮 ====== */
.sidebar-expand {
  position: fixed;
  left: 12px;
  top: 12px;
  z-index: 30;
  width: 36px;
  height: 36px;
  border-radius: 10px;
  border: 1px solid #e7e8ee;
  background: #ffffff;
  color: #4d6bfe;
  font-size: 16px;
  cursor: pointer;
  box-shadow: 0 6px 16px rgba(30, 34, 60, .12);
  transition: all .2s;
}
.sidebar-expand:hover {
  transform: translateY(-1px);
  box-shadow: 0 8px 20px rgba(30, 34, 60, .16);
}

/* ====== 主布局 ====== */
.chat-wrap {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}
.layout { flex: 1; display: flex; min-width: 0; min-height: 0; }

/* ====== 聊天面板 ====== */
.chat-panel { flex: 1; display: flex; flex-direction: column; min-width: 0; }

/* ====== 会话标题栏（聊天区顶部） ====== */
.session-header {
  flex-shrink: 0;
  width: 100%;
  max-width: 780px;
  margin: 0 auto;
  padding: 20px 0 6px;
  display: flex;
  align-items: center;
  gap: 10px;
}
.session-title {
  flex: 1;
  min-width: 0;
  font-size: 16px;
  font-weight: 700;
  color: #1f2232;
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
  padding: 4px 0 8px;
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
.done-card.cancelled {
  border-color: #f0e2dd;
  background: linear-gradient(135deg, #fbf8f6, #f6f1ee);
}
.done-card.cancelled .done-check {
  background: #f2a97f;
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


/* ====== 执行控制条（暂停/继续/停止） ====== */
.run-controls {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  max-width: 780px;
  margin: 0 auto 10px;
  padding: 0 4px;
}
.run-state {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-size: 12px;
  font-weight: 600;
  color: #4d6bfe;
  background: #eef2ff;
  padding: 5px 12px;
  border-radius: 999px;
}
.run-state i {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #4d6bfe;
  animation: event-pulse 1.1s infinite ease-in-out;
}
.run-state.paused { color: #b45309; background: #fef3c7; }
.run-state.paused i { background: #f59e0b; animation: none; }
.ctl-btn {
  flex-shrink: 0;
  font-family: inherit;
  font-size: 12px;
  font-weight: 600;
  padding: 5px 16px;
  border-radius: 999px;
  border: 1px solid #e4e6eb;
  background: #fff;
  color: #55586b;
  cursor: pointer;
  transition: all 0.2s;
}
.ctl-btn:hover:not(:disabled) { border-color: #4d6bfe; color: #4d6bfe; }
.ctl-btn.pause { color: #4d6bfe; border-color: #ccd5f8; background: #eef2ff; }
.ctl-btn.pause:hover:not(:disabled) { background: #dfe7ff; border-color: #b6c4fa; }
.ctl-btn.resume { color: #17803d; border-color: #c8e6d3; background: #e8f7ee; }
.ctl-btn.resume:hover:not(:disabled) { background: #d2f0de; }
.ctl-btn.stop { color: #b42318; border-color: #f0d0cc; background: #fdeeee; }
.ctl-btn.stop:hover:not(:disabled) { background: #fbdcdc; border-color: #e9b8b2; }
.ctl-btn:disabled { opacity: 0.5; cursor: not-allowed; }

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
/* 执行/暂停期间占据发送位的胶囊按钮 */
.input-ctl-btn {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 36px;
  min-width: 64px;
  padding: 0 14px;
  border: 1px solid;
  border-radius: 999px;
  font-family: inherit;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}
.input-ctl-btn.pause { color: #4d6bfe; border-color: #ccd5f8; background: #eef2ff; }
.input-ctl-btn.pause:hover:not(:disabled) { background: #dfe7ff; border-color: #b6c4fa; }
.input-ctl-btn.resume { color: #17803d; border-color: #c8e6d3; background: #e8f7ee; }
.input-ctl-btn.resume:hover:not(:disabled) { background: #d2f0de; }
.input-ctl-btn:disabled { opacity: 0.5; cursor: not-allowed; }
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

/* 待裁决文件编辑下拉框:位于输入框上方,与输入框同宽居中 */
.pending-edits {
  flex-shrink: 0;
  max-width: 780px;
  margin: 0 auto 6px;
  border: 1px solid #eceef4;
  border-radius: 10px;
  background: #fff;
  box-shadow: 0 4px 16px rgba(30, 34, 60, 0.06);
  overflow: hidden;
}
.pending-head {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 8px 12px;
  border: none;
  background: #f8f9fc;
  cursor: pointer;
  font-size: 12px;
  color: #262832;
  text-align: left;
}
.pending-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #f59e0b;
  flex-shrink: 0;
}
.pending-title { font-weight: 600; }
.pending-hint { color: #9ca3af; flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pending-chevron { color: #9ca3af; transition: transform .2s; }
.pending-chevron.rotated { transform: rotate(180deg); }
.pending-list {
  max-height: 220px;
  overflow-y: auto;
}
.pending-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 12px;
  border-top: 1px solid #f0f1f5;
  font-size: 12px;
}
.pending-file {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #262832;
  font-weight: 500;
}
.pending-lines { flex-shrink: 0; font-style: normal; }
.pending-lines .plus { font-style: normal; color: #17803d; font-variant-numeric: tabular-nums; }
.pending-lines .minus { font-style: normal; color: #b42318; font-variant-numeric: tabular-nums; }
.pending-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 7px 12px;
  border-top: 1px solid #f0f1f5;
  background: #f8f9fc;
}

.decision-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}
.decision-btn {
  border: none;
  border-radius: 6px;
  padding: 5px 14px;
  font-size: 12px;
  cursor: pointer;
  transition: all .2s;
}
.decision-btn.keep { background: #e8f7ee; color: #17803d; }
.decision-btn.keep:hover { background: #d2f0de; }
.decision-btn.undo { background: #fdeeee; color: #b42318; }
.decision-btn.undo:hover { background: #fbdcdc; }
.decision-btn:disabled { opacity: .5; cursor: not-allowed; }
</style>














