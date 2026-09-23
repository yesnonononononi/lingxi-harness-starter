<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { getUser, removeToken } from '../utils/auth'
import request from '../utils/request'
import { acceptEdit, rejectEdit, acceptTurn, rejectTurn, listPendingEdits, getEditDetail } from '../api/fileEdit'
import { listDirs, selectWorkspace, getCurrentWorkspace } from '../api/workspace'
import ContextRing from '../components/ContextRing.vue'
import FileDiff from '../components/FileDiff.vue'
import MarkdownContent from '../components/MarkdownContent.vue'
import ToolIcon from '../components/ToolIcon.vue'
import WelcomeWidget from '../components/WelcomeWidget.vue'

const router = useRouter()
const user = ref(getUser() || { name: '' })

// ====== 实时事件(SSE) ======
const sseStatus = ref('未连接')
const events = ref([])
const input = ref('')
const inputFocused = ref(false)
const sending = ref(false)
// ====== 命令审批（ack）模式 ======
// 工具执行前的人工确认级别：随 /agent/chat 提交，作用于该轮 agent 每次命令执行
const ACK_MODE_KEY = 'lingxi_ack_mode'
const ACK_MODES = [
  { value: 'FULL_ACCESS', label: '完全访问', short: '无需确认', desc: '所有命令自动执行，不进行人工确认' },
  { value: 'PRE_EXEC_CONFIRM', label: '执行前确认', short: '全部确认', desc: '所有命令执行前都需人工批准' },
  { value: 'DANGEROUS_BLOCK', label: '危险命令确认', short: '危险拦截', desc: '仅危险命令执行前需人工确认' },
]
const ackMode = ref(localStorage.getItem(ACK_MODE_KEY) || 'DANGEROUS_BLOCK')
const ackOpen = ref(false)
const ackPickerEl = ref(null)
const currentAck = computed(() => ACK_MODES.find((m) => m.value === ackMode.value) || ACK_MODES[0])
function setAckMode(mode) {
  ackMode.value = mode
  ackOpen.value = false
  try {
    localStorage.setItem(ACK_MODE_KEY, mode)
  } catch {
    // ignore storage errors (e.g. private mode)
  }
}
// ====== 执行控制（停止当前会话） ======
const executing = ref(false)
const ctlBusy = ref(false)
const logRef = ref(null)
const workdir = ref('')
const editingDir = ref(false)
const dirInput = ref('')
const savingDir = ref(false)
// ====== 当前工作区（宿主目录 / 容器 / 模式） ======
// 上下文用量（环形图）：挂载时拉取一次，之后由 CONTEXT_UPDATE 事件驱动更新
const ctxRatio = ref(0)
const ctxTokens = ref(0)
const ctxMax = ref(0)
const workspaceHost = ref('')
const workspaceContainer = ref('')
const workspaceMode = ref('') // 'docker' | 'local' | ''
// ====== “选择工作区”目录树弹窗 ======
const dirPickerOpen = ref(false)
const pickerPath = ref('') // 当前浏览的宿主机绝对路径；'' 表示盘符根视图
const pickerParent = ref(null)
const pickerDirs = ref([])
const pickerLoading = ref(false)
const pickerError = ref('')
const pickerSelecting = ref(false)
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
// 所有仍待裁决的文件编辑(跨轮),「文件列表」抽屉的数据源（与对话流解耦）
const drawerPendingEdits = ref([])
const pendingEdits = computed(() =>
  drawerPendingEdits.value.filter((e) => e.decision === 'pending')
)

// ====== 顶部抽屉（dock）：待审阅文件 ======
// 可展开，展开后宽度 = 聊天面板的 2 倍；agent 尚未产出文件时显示 (0)。
const fileDockOpen = ref(false)

// 抽屉内展开状态：与对话流里 FILE_EDIT 卡片共用同一个 item 实例，
// 若直接翻 item.open 会让对话卡片同步展开（同一个 <FileDiff> 重复挂载、串 diff）。
// 用本地 Set 记录抽屉里展开的 recordId，懒加载仍复用 loadEditContent 以复用请求。
const dockExpanded = ref(new Set())
async function toggleDockEdit(item) {
  const id = item.recordId
  if (!id) return
  const next = new Set(dockExpanded.value)
  if (next.has(id)) {
    next.delete(id)
  } else {
    if (!item.loaded && !item.loading) await loadEditContent(item)
    next.add(id)
  }
  dockExpanded.value = next
}

// 抽屉面板（待裁决审批框）通过 Teleport 挂到 .input-area 下，宽度自然撑满 chat-panel。
// inputAreaEl 既是模板 ref，也是 Teleport 的挂载目标。
const inputAreaEl = ref(null)

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
    const newDecision = accept ? 'ACCEPTED' : 'REJECTED'
    item.decision = newDecision
    events.value.forEach((e) => {
      if (e.type === 'FILE_EDIT' && e.recordId === item.recordId) {
        e.decision = newDecision
      }
    })
    drawerPendingEdits.value.forEach((e) => {
      if (e.recordId === item.recordId) {
        e.decision = newDecision
      }
    })
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
    const newDecision = accept ? 'ACCEPTED' : 'REJECTED'
    events.value.forEach((e) => {
      if (e.type === 'FILE_EDIT' && e.turnId === executionId) e.decision = newDecision
    })
    drawerPendingEdits.value.forEach((e) => {
      if (e.turnId === executionId) e.decision = newDecision
    })
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
    const newDecision = accept ? 'ACCEPTED' : 'REJECTED'
    events.value.forEach((e) => {
      if (e.type === 'FILE_EDIT' && e.decision === 'pending') e.decision = newDecision
    })
    drawerPendingEdits.value.forEach((e) => {
      if (e.decision === 'pending') e.decision = newDecision
    })
  } catch (err) {
    appendLog({ time: now(), type: 'ERROR', text: `统一决策失败:${err.message || err}` })
  }
}

// 按需拉取单条编辑的完整旧/新内容(SSE 卡片已内联内容,此处仅兜底历史回放)
async function loadEditContent(item) {
  if (item.loaded || item.loading || !item.recordId) return
  item.loading = true
  try {
    const data = await getEditDetail(currentSessionId.value, item.recordId)
    item.filePath = data?.filePath || item.filePath
    item.oldContent = data?.oldContent ?? ''
    item.newContent = data?.newContent ?? ''
    item.plusLines = data?.plusLines ?? item.plusLines
    item.minusLines = data?.minusLines ?? item.minusLines
    item.loaded = true
  } catch (err) {
    item.loaded = false
    appendLog({ time: now(), type: 'ERROR', text: `加载文件 diff 失败:${err.message || err}` })
  } finally {
    item.loading = false
  }
}

// 点击条目:展开/收起并懒加载 diff(时间线卡片与待裁决列表共用)
async function toggleEditDiff(item) {
  item.open = !item.open
  if (item.open) await loadEditContent(item)
}

function onDocClick(e) {
  if (ackPickerEl.value && !ackPickerEl.value.contains(e.target)) {
    ackOpen.value = false
  }
  if (modePickerEl.value && !modePickerEl.value.contains(e.target)) {
    modeOpen.value = false
  }
}

// thinking 卡片默认收起，点击头部展开/收起（与工具卡片交互一致）
function toggleThinking(item) {
  item.thinkingOpen = !item.thinkingOpen
}

// 工具归类：read(读取文件) / edit(修改文件) / command(执行命令) /
// choice(提出选择) / other，用于选择卡片图标与中文动作名。
// 所有非 read 的工具共用同一套图标：执行中旋转 loading，结束后 √（与执行命令一致）。
function toolKind(item) {
  const name = (item.toolName || '').toLowerCase()
  if (name.includes('read')) return 'read'
  if (name === 'require_choice') return 'choice'
  if (name.includes('edit') || name.includes('write') || name.includes('update')
    || name.includes('insert') || name.includes('replace')) return 'edit'
  if (name.includes('command') || name.includes('exec') || name.includes('terminal')
    || name.includes('shell') || name.includes('bash')) return 'command'
  return 'other'
}

// 内核工具名 -> 中文动作名（按工具名精确匹配，优先于按 kind 的归类名）
const TOOL_NAME_LABELS = {
  require_choice: '提出选择',
}
const TOOL_LABELS = { read: '读取文件', edit: '修改文件', command: '执行命令' }
// 卡片头部动作名：提出选择 / 读取文件 / 修改文件 / 执行命令，其余回退到原始工具名
function toolLabel(item) {
  const name = (item.toolName || '').toLowerCase()
  return TOOL_NAME_LABELS[name] || TOOL_LABELS[toolKind(item)] || item.toolName || '工具调用'
}

// 提取卡片直接展示的目标芯片（如文件名、执行命令、搜索关键字等），避免直接暴露内部具体工具名称
function toolTarget(item) {
  if (item.fileName) return item.fileName
  if (item.command) return item.command
  if (item.args) {
    let val = item.args
    if (typeof val === 'string') {
      try { val = JSON.parse(val) } catch {}
    }
    if (val && typeof val === 'object') {
      const q = val.query || val.pattern || val.keyword
      if (q) return `"${q}"`
      const d = val.directoryPath || val.dir || val.SearchPath || val.path
      if (d) return String(d).split(/[\\/]/).pop() || String(d)
      const u = val.url || val.Url
      if (u) return String(u)
      const p = val.prompt || val.Prompt
      if (p) return String(p).slice(0, 30)
    }
  }
  return '执行操作'
}

// 文件类工具（读取/修改文件）：卡片不展开原始参数/输出，内容由专用视图（文件 diff 卡片）呈现
function isFileTool(item) {
  const kind = toolKind(item)
  return kind === 'read' || kind === 'edit'
}

// 一次文件修改会产生两条事件：TOOL_STARTED(edit_file) 先到，卡片先显示「执行中」；
// 落盘后 FILE_EDIT 才带着旧/新内容到达。若两条各自渲染，同一次修改就会出现
// 「修改文件 X.java 完成」+「修改文件 X.java +1 -9 已应用」两张卡。
// 因此 FILE_EDIT 到达时把它对应的工具卡标记为已合并（模板不再渲染），
// 由 diff 卡作为唯一消息体承载「点击展开 diff」。
function isEditTool(item) {
  return item.type === 'TOOL_STARTED' && toolKind(item) === 'edit'
}

// 定位本次编辑对应的工具卡并标记合并。
// FILE_EDIT 事件本身不带 executionId，但它的 turnId 就是该工具调用所属的 executionId
// （DefaultToolExecutionManager 里 turnId 取自 command.executionId），
// 所以按「同一轮 + 同名文件」精确匹配；历史回放的 TOOL 消息没有参数与文件名，
// 退化为用最近一个无名编辑卡兜底（它没有任何可展示信息，被替换掉不会丢内容）。
function mergeEditToolCard(fileEdit) {
  const base = fileNameOf(fileEdit.filePath).toLowerCase()
  const turn = fileEdit.turnId || ''
  let exact = null
  let fallback = null
  for (const item of events.value) {
    if (!isEditTool(item) || item.merged) continue
    if (item.fileName) {
      // 取最早那个未合并的同名工具卡，保证同一文件多次修改按先后顺序一一配对
      if (!exact && base && item.fileName.toLowerCase() === base
        && (!turn || item.executionId === turn)) {
        exact = item
      }
      continue
    }
    fallback = item
  }
  const target = exact || fallback
  if (target) target.merged = true
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
      if (evt.type === 'WORKSPACE_CHANGED') {
        // 工作区（容器/宿主目录）被切换：同步状态；多页面通过 SSE 保持展示一致
        applyWorkspace(evt.data)
        return
      }
      if (evt.type === 'WORKDIR_CHANGED') {
        workdir.value = evt.data?.workdir || workdir.value
        // 工作目录变更后工作区快照可能随之变化（local 模式下宿主目录即工作目录），
        // 重新拉取一次保持工作区栏展示一致
        loadWorkspace()
        return
      }
      // 只展示当前会话的事件；切换会话后旧会话的延迟事件被忽略
      if (evt.sessionId && currentSessionId.value && evt.sessionId !== currentSessionId.value) return
      // 上下文压缩进度：SQUEEZE_STARTED 携带压缩前用量、SQUEEZE_COMPLETED 携带压缩后用量，
      // 环形图据此做过渡动画更新（压缩完成后环会平滑回落）
      if (evt.type === 'CONTEXT_UPDATE') {
        const usage = evt.data || {}
        if (typeof usage.ratio === 'number') ctxRatio.value = usage.ratio
        if (typeof usage.tokenCount === 'number') ctxTokens.value = usage.tokenCount
        if (typeof usage.maxTokens === 'number') ctxMax.value = usage.maxTokens
        return
      }
      // 跟踪当前会话执行状态：STARTED -> 运行中，结束事件 -> 空闲
      if (evt.type === 'EXECUTION_STARTED') {
        executing.value = true
      } else if (terminalEventTypes.has(evt.type)) {
        executing.value = false
      }
      const item = { time: now(), type: evt.type, text: formatEvent(evt), executionId: evt.executionId || '' }
      if (evt.type === 'TOOL_STARTED') {
        item.toolName = evt.data?.toolName || ''
        item.args = evt.data?.args || ''
        item.fileName = extractFileName(evt.data?.args)
        item.fileRange = extractFileRange(evt.data?.args)
        item.command = evt.data?.command || extractCommand(evt.data?.args)
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
          if (!tool.command && evt.data?.command) {
            tool.command = evt.data.command
          }
        }
        return
      }
      if (evt.type === 'PARTIAL_THINKING' || evt.type === 'PARTIAL_TEXT') {
        // accumulate streaming chunks into the last AGENT_MESSAGE of the same execution
        // (typewriter effect); create the bubble on first chunk if it does not exist yet
        let target = findLastAgentMessage(evt.executionId)
        if (!target) {
          appendLog({
            time: now(),
            type: 'AGENT_MESSAGE',
            executionId: evt.executionId || '',
            text: '',
            thinking: '',
            streaming: true,
            thinkingOpen: true,
            closed: false
          })
          target = findLastAgentMessage(evt.executionId)
        }
        const chunk = evt.data?.text || ''
        if (evt.type === 'PARTIAL_THINKING') {
          target.thinking = (target.thinking || '') + chunk
          target.thinkingOpen = true
        } else {
          target.text = (target.text || '') + chunk
        }
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
          if (evt.data?.thinking && !inflight.thinking) inflight.thinking = evt.data.thinking
          if (evt.data?.text && !inflight.text) inflight.text = evt.data.text
          return
        }
        // 非流式（无 PARTIAL 事件）的完整消息：直接作为独立消息体展示并标记已收尾
        item.text = evt.data?.text || ''
        item.thinking = evt.data?.thinking || ''
        item.thinkingOpen = false
        item.streaming = false
        item.closed = true
        // 若既无文本也无思考内容，不追加空白气泡
        if (!item.text && !item.thinking) return
      }
      if (evt.type === 'EXECUTION_COMPLETED' || evt.type === 'EXECUTION_FAILED' || evt.type === 'EXECUTION_CANCELLED') {
        // a round of execution is done: immediately settle any in-flight markdown
        settleMarkdown(evt.executionId || '')
      }
      if (evt.type === 'FILE_EDIT') {
        // render a Monaco DiffEditor card showing the file change;
        // edits are already applied on disk, recordId enables accept/reject
        const editCard = {
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
          loaded: true,
          loading: false,
        }
        // 同一次修改只保留一个消息体：先合并掉对应的「修改文件」工具卡
        mergeEditToolCard(editCard)
        appendLog(editCard)
        if (editCard.decision === 'pending') {
          const idx = drawerPendingEdits.value.findIndex((e) => e.recordId === editCard.recordId)
          if (idx >= 0) {
            drawerPendingEdits.value[idx] = editCard
          } else {
            drawerPendingEdits.value.unshift(editCard)
          }
        }
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
        drawerPendingEdits.value.forEach((item) => {
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

// 提取工具执行命令（供工具栏 hold 位置直接预览展示）
function extractCommand(args) {
  if (!args) return ''
  let value = args
  if (typeof value === 'string') {
    try {
      value = JSON.parse(value)
    } catch {
      if (!args.trim().startsWith('{') && !args.trim().startsWith('[')) {
        return args.trim()
      }
      return ''
    }
  }
  if (!value) return ''
  if (typeof value === 'string') return value.trim()
  if (typeof value === 'object') {
    const cmd = value.command || value.cmd || value.commandLine || value.script || value.action || ''
    if (typeof cmd === 'string') return cmd.trim()
    return ''
  }
  return ''
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
      return d.text || ''
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
  // 执行期间发送位是方形停止按钮，Enter 也不应触发新任务
  if (!text || sending.value || executing.value) return

  sending.value = true
  appendLog({ time: now(), type: 'USER', text })
  input.value = ''

  try {
    const body = {
      input: text,
      streaming: true,
      modelProvider: 'default-streaming',
      commandApprovalPolicy: ackMode.value,
    }
    if (currentSessionId.value) body.sessionId = currentSessionId.value
    if (currentSessionName.value) body.sessionName = currentSessionName.value
    const data = await request.post('/agent/chat', body)
    // 后端分配/确认 sessionId；会话列表以 conversation store 为准，延迟刷新
    if (data?.sessionId) {
      currentSessionId.value = data.sessionId
      currentSessionName.value = data.sessionName || currentSessionName.value || '新会话'
      loadSessions()
      loadContextUsage()
    }
    appendLog({ time: now(), type: 'SYS', text: `任务已提交：${data?.message || 'ok'}` })
  } catch (e) {
    appendLog({ time: now(), type: 'ERROR', text: `发送失败：${e?.message || e}` })
  } finally {
    sending.value = false
  }
}

// ====== 执行控制 ======
async function stopAgent() {
  if (!currentSessionId.value || ctlBusy.value) return
  ctlBusy.value = true
  try {
    const data = await request.post('/agent/stop', null, {
      params: { sessionId: currentSessionId.value },
    })
    const applied = !!data?.applied
    appendLog({
      time: now(),
      type: 'SYS',
      text: `已请求停止${applied ? '' : '（当前无运行中的执行，已忽略）'}`,
    })
    if (!applied) {
      executing.value = false
      return
    }
    executing.value = false
  } catch (e) {
    // 404：该会话此刻没有运行中的执行；其它错误原样提示
    executing.value = false
    appendLog({ time: now(), type: 'ERROR', text: `停止失败：${e?.message || e}` })
  } finally {
    ctlBusy.value = false
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
  drawerPendingEdits.value = []
  executing.value = false
  showSessionRename.value = false
  loadContextUsage()
  appendLog({ time: now(), type: 'SYS', text: '已新建会话，发送消息后将自动创建 sessionId' })
}

async function switchSession(session) {
  if (!session.sessionId || session.sessionId === currentSessionId.value) return
  const targetSessionId = session.sessionId
  currentSessionId.value = targetSessionId
  currentSessionName.value = session.sessionName || session.sessionId.slice(0, 8)
  events.value = []
  drawerPendingEdits.value = []
  loadContextUsage()
  // 先清空旧会话状态，再从后端恢复目标会话的真实执行状态。
  executing.value = false
  showSessionRename.value = false

  // 历史消息、执行状态以及文件待决变更彼此独立恢复；某一接口失败不影响其它模块
  const [messagesResult, statusResult, editsResult] = await Promise.allSettled([
    request.get(`/agent/sessions/${targetSessionId}/messages`),
    request.get('/agent/executions/status', { params: { sessionId: targetSessionId } }),
    listPendingEdits(targetSessionId),
  ])
  // 用户可能在请求期间又切换了会话，禁止迟到响应污染当前页面。
  if (currentSessionId.value !== targetSessionId) return

  // 1. 初始化待裁决编辑抽屉数据源 (与对话流解耦，不向 events 尾部乱堆卡片)
  if (editsResult.status === 'fulfilled') {
    const data = editsResult.value
    drawerPendingEdits.value = (data?.edits || []).map((e) => ({
      recordId: e.recordId,
      turnId: e.turnId,
      filePath: e.filePath,
      version: e.version,
      plusLines: e.plusLines ?? 0,
      minusLines: e.minusLines ?? 0,
      decision: 'pending',
      deciding: false,
      open: false,
      loaded: false,
      loading: false,
      oldContent: '',
      newContent: '',
    }))
  }

  // 2. 还原对话流历史消息 (精准关联 toolCalls 参数并按实际位置排版)
  if (messagesResult.status === 'fulfilled') {
    const data = messagesResult.value
    // 建立 toolCalls 映射表与顺序队列，完整还原历史调用入参 (arguments) 与工具目标
    const toolCallsById = new Map()
    let pendingAiToolCalls = []

    for (const m of data?.messages || []) {
      if (m.type === 'USER') {
        events.value.push({ time: '', type: 'USER', text: m.text || '' })
      } else if (m.type === 'AI') {
        if (Array.isArray(m.toolCalls)) {
          pendingAiToolCalls = [...m.toolCalls]
          for (const tc of m.toolCalls) {
            if (tc && tc.id) toolCallsById.set(tc.id, tc)
          }
        } else {
          pendingAiToolCalls = []
        }
        // 仅在有思考内容或实际文本时渲染，避免空 AI 气泡
        const hasText = m.text && m.text.trim()
        const hasThinking = m.thinking && m.thinking.trim()
        if (hasText || hasThinking) {
          events.value.push({
            time: '', type: 'AGENT_MESSAGE', executionId: '',
            text: m.text || '', thinking: m.thinking || '',
            streaming: false, thinkingOpen: false, closed: true,
          })
        }
      } else if (m.type === 'TOOL') {
        let tc = m.id ? toolCallsById.get(m.id) : null
        if (!tc && pendingAiToolCalls.length > 0) {
          const idx = pendingAiToolCalls.findIndex(
            (t) => (t.name || '').toLowerCase() === (m.name || '').toLowerCase()
          )
          if (idx >= 0) {
            tc = pendingAiToolCalls.splice(idx, 1)[0]
          } else {
            tc = pendingAiToolCalls.shift()
          }
        }
        const args = tc?.arguments || ''
        const toolName = m.name || tc?.name || 'tool'
        const fileName = extractFileName(args)
        const fileRange = extractFileRange(args)
        const command = extractCommand(args)

        // 若历史工具调用为修改文件且该文件存在对应的待裁决记录，将 diff 卡片就地放于该轮位置
        if (toolKind({ toolName }) === 'edit') {
          const editRecord = drawerPendingEdits.value.find(
            (e) => !e._placedInTimeline && (e.filePath.endsWith(fileName) || fileNameOf(e.filePath) === fileName)
          )
          if (editRecord) {
            editRecord._placedInTimeline = true
            events.value.push({
              time: '',
              type: 'FILE_EDIT',
              executionId: editRecord.turnId || '',
              recordId: editRecord.recordId,
              turnId: editRecord.turnId,
              filePath: editRecord.filePath,
              oldContent: '',
              newContent: '',
              plusLines: editRecord.plusLines ?? 0,
              minusLines: editRecord.minusLines ?? 0,
              decision: editRecord.decision || 'pending',
              deciding: false,
              open: false,
              loaded: false,
              loading: false,
            })
            continue
          }
        }

        events.value.push({
          time: '',
          type: 'TOOL_STARTED',
          executionId: '',
          toolName,
          args,
          fileName,
          fileRange,
          command,
          status: 'done',
          open: false,
          output: m.text || '',
        })
      }
    }
  } else {
    appendLog({ time: now(), type: 'ERROR', text: `加载历史消息失败：${messagesResult.reason?.message || messagesResult.reason}` })
  }

  if (statusResult.status === 'fulfilled') {
    executing.value = !!statusResult.value?.running
  } else {
    appendLog({ time: now(), type: 'ERROR', text: `加载执行状态失败：${statusResult.reason?.message || statusResult.reason}` })
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


// ====== 工作目录 / 工作区 ======
async function loadWorkdir() {
  try {
    const data = await request.get('/agent/workdir')
    workdir.value = data?.workdir || ''
  } catch (e) {
    workdir.value = ''
  }
}

// 拉取当前工作区状态（宿主目录 / 容器 / 模式），供启动时初始化展示
async function loadWorkspace() {
  try {
    applyWorkspace(await getCurrentWorkspace())
  } catch (e) {
    // 后端尚未支持该接口时不阻断主流程
  }
}

function applyWorkspace(data) {
  if (!data) return
  workspaceHost.value = data.hostDir || ''
  workspaceContainer.value = data.containerName || ''
  workspaceMode.value = data.mode || ''
  if (data.workDir) workdir.value = data.workDir
}

// ====== 上下文用量（环形图） ======
// 页面挂载 / 切换会话 / 新建会话 / 会话首次创建后调用，取当前会话的 token 占用
async function loadContextUsage() {
  try {
    const data = await request.get('/agent/context/usage', {
      params: currentSessionId.value ? { sessionId: currentSessionId.value } : {},
    })
    ctxRatio.value = typeof data?.ratio === 'number' ? data.ratio : 0
    ctxTokens.value = typeof data?.tokenCount === 'number' ? data.tokenCount : 0
    ctxMax.value = typeof data?.maxTokens === 'number' ? data.maxTokens : 0
  } catch (e) {
    // 后端未提供该接口时不阻断主流程
  }
}

// 工作目录栏展示：docker 模式优先显示宿主挂载目录，local 模式即工作目录本身
function displayedDir() {
  if (workspaceHost.value && workspaceHost.value !== workdir.value) return workspaceHost.value
  return workdir.value || ''
}

function dirFullTitle() {
  const parts = []
  if (workspaceHost.value) parts.push(`宿主目录：${workspaceHost.value}`)
  if (workdir.value && workdir.value !== workspaceHost.value) parts.push(`容器内目录：${workdir.value}`)
  return parts.join('\n')
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

// ====== 选择工作区（目录树弹窗） ======
async function openDirPicker() {
  pickerError.value = ''
  dirPickerOpen.value = true
  await browseDirs('')
}

async function browseDirs(path) {
  pickerLoading.value = true
  pickerError.value = ''
  try {
    const data = await listDirs(path)
    pickerPath.value = data?.path || ''
    pickerParent.value = data?.parent || null
    pickerDirs.value = data?.directories || []
  } catch (e) {
    pickerError.value = e?.message || '读取目录失败'
    pickerDirs.value = []
  } finally {
    pickerLoading.value = false
  }
}

async function pickerGoUp() {
  // 非根目录 -> 父级；盘符根 -> 回盘符列表
  if (pickerParent.value) await browseDirs(pickerParent.value)
  else await browseDirs('')
}

async function pickerEnterDir(dir) {
  if (!dir || !dir.path) return
  await browseDirs(dir.path)
}

async function pickerSelectCurrent() {
  if (pickerSelecting.value) return
  if (!pickerPath.value) {
    pickerError.value = '请进入一个文件夹后再选择'
    return
  }
  pickerSelecting.value = true
  pickerError.value = ''
  try {
    const data = await selectWorkspace(pickerPath.value)
    applyWorkspace(data)
    // 同步容器内工作目录并记录系统日志
    await loadWorkdir()
    dirPickerOpen.value = false
    const action = data?.reused ? '复用已有容器' : '新建沙箱容器'
    appendLog({
      time: now(),
      type: 'SYS',
      text: `工作区已切换：${data?.hostDir || pickerPath.value}${data?.containerName ? `（${data.containerName}，${action}）` : ''}`,
    })
  } catch (e) {
    pickerError.value = e?.message || '工作区切换失败'
  } finally {
    pickerSelecting.value = false
  }
}

onMounted(() => {
  connectEvents()
  loadWorkdir()
  loadWorkspace()
  loadSessions()
  loadContextUsage()
  document.addEventListener('click', onDocClick)
})

onBeforeUnmount(() => {
  if (mdSettleTimer) clearTimeout(mdSettleTimer)
  if (es) {
    es.close()
  }
  document.removeEventListener('click', onDocClick)
})
</script>

<template>
  <div class="home">
    <!-- 左侧边栏：极简黑白美学 -->
    <aside class="sidebar" :class="{ collapsed: !showSessions }">
      <!-- 品牌区 -->
      <div class="sidebar-brand">
        <div class="logo">LX</div>
        <div class="brand-copy">
          <div class="brand-name">LingXi</div>
          <div class="brand-sub">Coding Agent</div>
        </div>
        <button class="brand-collapse" title="收起侧边栏" @click="showSessions = false">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"/></svg>
        </button>
      </div>

      <!-- 新建会话操作条 -->
      <div class="explorer-actions">
        <button class="new-session-btn" @click="newSession">
          <svg class="ns-icon" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
          <span>新建会话</span>
        </button>
      </div>

      <!-- 会话列表 -->
      <div class="session-scroll">
        <div class="side-section-label">
          <span class="label-text">历史会话</span>
          <span class="label-count">{{ sessions.length }}</span>
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
            <span class="session-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
            </span>
            <span class="session-name">{{ s.sessionName || s.sessionId.slice(0, 8) }}</span>
            <span class="session-id">{{ s.sessionId.slice(0, 6) }}</span>
          </div>
          <div v-if="sessions.length === 0" class="session-empty">
            <span>暂无会话，点击上方开始</span>
          </div>
        </div>
      </div>

      <!-- 底部极简状态栏 -->
      <div class="sidebar-foot">
        <div class="statusbar">
          <span class="sb-item" :title="`SSE 连接状态: ${sseStatus}`">
            <span class="foot-dot" :class="{ online: sseStatus === '已连接' }"></span>
            <span>{{ sseStatus }}</span>
          </span>
          <span class="sb-spacer"></span>
          <span class="sb-user" :title="user.name || '访客'">{{ (user.name || '客').slice(0, 1) }}</span>
          <button class="sb-btn" title="收起侧边栏" @click="showSessions = false">
            <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"/></svg>
          </button>
          <button class="sb-btn danger" title="退出登录" @click="handleLogout">
            <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" y1="12" x2="9" y2="12"/></svg>
          </button>
        </div>
      </div>
    </aside>

    <!-- 侧栏收起后的浮动展开按钮 -->
    <button v-if="!showSessions" class="sidebar-expand" title="展开侧边栏" @click="showSessions = true">
      <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="3" y1="12" x2="21" y2="12"/><line x1="3" y1="6" x2="21" y2="6"/><line x1="3" y1="18" x2="21" y2="18"/></svg>
    </button>

    <main class="chat-wrap">
      <div class="layout">
        <div class="chat-panel" :class="{ empty: !hasChat }">
          <!-- 极简顶部导航头：会话标题 + 工作区合一 -->
          <div class="top-nav-bar">
            <!-- 会话标题与重命名 -->
            <div class="session-header">
              <template v-if="!showSessionRename">
                <span class="session-title">{{ currentSessionName || '新会话' }}</span>
                <button v-if="currentSessionId" class="rename-btn" @click="startRename" title="重命名会话">
                  <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 20h9"/><path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"/></svg>
                </button>
              </template>
              <template v-else>
                <input v-model="renameInput" class="rename-input" placeholder="输入会话名称" @keydown.enter="saveRename" @keydown.esc="cancelRename" />
                <button class="rename-btn primary" @click="saveRename">保存</button>
                <button class="rename-btn" @click="cancelRename">取消</button>
              </template>
            </div>

            <!-- 工作目录与沙箱状态 -->
            <div class="workdir-bar">
              <template v-if="!editingDir">
                <span v-if="workspaceContainer" class="ws-tag" :title="`沙箱容器：${workspaceContainer}`">
                  <span class="tag-dot"></span>{{ workspaceContainer }}
                </span>
                <span v-else-if="workspaceMode === 'local'" class="ws-tag">
                  <span class="tag-dot"></span>local
                </span>
                <span class="workdir-path" :title="dirFullTitle()">{{ displayedDir() || '未设置工作区' }}</span>
                <span
                  v-if="workspaceContainer && workdir && workdir !== workspaceHost"
                  class="ws-tag dir"
                  :title="`容器内工作目录：${workdir}`"
                >{{ workdir }}</span>
                <div class="workdir-actions">
                  <button
                    class="workdir-btn primary"
                    :disabled="pickerSelecting"
                    @click="openDirPicker"
                    title="选择工作区文件夹"
                  >切换目录</button>
                  <button class="workdir-btn" @click="startEditDir" title="修改容器内的工作目录">修改</button>
                </div>
              </template>
              <template v-else>
                <span class="ws-tag edit" :title="workspaceHost">容器内</span>
                <input
                  v-model="dirInput"
                  class="workdir-input"
                  placeholder="输入容器内绝对路径"
                  @keydown.enter="saveWorkdir"
                  @keydown.esc="cancelEditDir"
                />
                <button class="workdir-btn primary" :disabled="savingDir" @click="saveWorkdir">{{ savingDir ? '保存中' : '保存' }}</button>
                <button class="workdir-btn" @click="cancelEditDir">取消</button>
              </template>
            </div>
          </div>

          <!-- 空消息引导：居中展示极简欢迎组件 -->
          <div v-if="!hasChat" class="empty-hero">
            <WelcomeWidget />
          </div>

          <!-- 消息列表滚动区 -->
          <div v-show="hasChat" ref="logRef" class="messages">
            <template v-for="(item, index) in events" :key="index">
              <!-- 消息体 1: 用户消息 (右侧对齐，极简纯黑胶囊) -->
              <div v-if="item.type === 'USER'" class="row row-user">
                <div class="user-msg-container">
                  <div class="bubble bubble-user">
                    <div class="bubble-text">{{ item.text }}</div>
                  </div>
                  <div class="user-avatar" title="用户">
                    <span>{{ (user.name || 'U').slice(0, 1).toUpperCase() }}</span>
                  </div>
                </div>
              </div>

              <!-- 消息体 2: 模型思考 / 回复 -->
              <div v-else-if="item.type === 'AGENT_MESSAGE'" class="row row-ai">
                <div class="ai-body">
                  <!-- 深度思考手风琴卡片 -->
                  <div v-if="item.thinking" class="tool-event thinking-event">
                    <div class="tool-card thinking-card" :class="{ open: item.thinkingOpen }">
                      <button class="tool-head thinking-head" @click="toggleThinking(item)">
                        <span class="thinking-spark-icon" aria-hidden="true">
                          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                            <path d="M12 2v4M12 18v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83M2 12h4M18 12h4M4.93 19.07l2.83-2.83M16.24 7.76l2.83-2.83"/>
                          </svg>
                        </span>
                        <span class="thinking-title">深度思考过程</span>
                        <span class="thinking-status" :class="item.streaming ? 'running' : 'done'">
                          <template v-if="item.streaming">
                            <span class="pulse-wave"><span></span><span></span><span></span></span>
                            <em>思考中...</em>
                          </template>
                          <template v-else>思考完成</template>
                        </span>
                        <span class="tool-chevron" :class="{ rotated: item.thinkingOpen }" aria-hidden="true">
                          <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"/></svg>
                        </span>
                      </button>
                      <div class="accordion-collapse" :class="{ open: item.thinkingOpen }">
                        <div class="accordion-content">
                          <div class="tool-body">
                            <div class="tool-body-inner">
                              <div class="thinking-quote-box">
                                <pre v-if="item.streaming" class="raw-text thinking-text streaming">{{ item.thinking }}</pre>
                                <MarkdownContent v-else class="thinking-text" :text="item.thinking" />
                              </div>
                            </div>
                          </div>
                        </div>
                      </div>
                    </div>
                  </div>

                  <!-- AI 核心内容 -->
                  <div v-if="item.text" class="bubble bubble-ai">
                    <div class="ai-header">
                      <div class="ai-id">
                        <span class="ai-badge-icon">LX</span>
                        <span class="ai-label">LingXi</span>
                      </div>
                      <div class="ai-header-right">
                        <span v-if="item.streaming" class="ai-chip streaming">
                          <span class="glow-dot"></span> 生成中...
                        </span>
                      </div>
                    </div>
                    <div class="ai-content-box">
                      <pre v-if="item.streaming" class="raw-text">{{ item.text }}</pre>
                      <MarkdownContent v-else :text="item.text" />
                    </div>
                  </div>
                </div>
              </div>

              <!-- 消息体 3: 工具调用卡片 -->
              <div v-else-if="item.type === 'TOOL_STARTED' && !item.merged" class="tool-event">
                <div class="tool-card" :class="{ open: item.open }">
                  <button class="tool-head" :class="toolKind(item)" @click="toggleTool(item)">
                    <span class="tool-icon" aria-hidden="true"><ToolIcon :kind="toolKind(item)" :status="item.status" size="14" /></span>
                    <!-- 不再展示具体工具名称（如"执行命令"、"读取文件"等），直接由图标引出目标芯片 -->
                    <span v-if="item.fileName" class="tool-file" :title="item.fileName">{{ item.fileName }}<span v-if="item.fileRange" class="tool-range">{{ item.fileRange }}</span></span>
                    <span v-else-if="item.command" class="tool-file tool-cmd-chip" :title="item.command">{{ item.command }}</span>
                    <span v-else class="tool-file tool-target-chip" :title="toolTarget(item)">{{ toolTarget(item) }}</span>
                    <span class="tool-status" :class="item.status">
                      <template v-if="item.status === 'running'">
                        <span class="running-dots"><i></i><i></i><i></i></span>
                        <em>执行中</em>
                      </template>
                      <template v-else>已完成</template>
                    </span>
                    <span v-if="!isFileTool(item)" class="tool-chevron" :class="{ rotated: item.open }" aria-hidden="true">
                      <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"/></svg>
                    </span>
                  </button>
                  <!-- 默认不展开；展开后，若为执行命令则仅展示命令结果不展示命令本身，带平滑过渡动画 -->
                  <div class="accordion-collapse" :class="{ open: !isFileTool(item) && item.open }">
                    <div class="accordion-content">
                      <div class="tool-body">
                        <div class="tool-body-inner">
                          <template v-if="toolKind(item) === 'command'">
                            <div class="tool-section">
                              <div class="tool-section-title">执行输出</div>
                              <pre class="tool-code tool-output-text">{{ item.status === 'running' ? '命令执行中，等待返回...' : (item.output || '（命令已执行完毕，无输出内容）') }}</pre>
                            </div>
                          </template>
                          <template v-else>
                            <div class="tool-section">
                              <div class="tool-section-title">入参 Payload</div>
                              <pre class="tool-code">{{ prettyArgs(item.args) }}</pre>
                            </div>
                            <div class="tool-section">
                              <div class="tool-section-title">输出 Response</div>
                              <pre class="tool-code tool-output-text">{{ item.status === 'running' ? '等待工具执行返回...' : (item.output || '（无输出内容）') }}</pre>
                            </div>
                          </template>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
              </div>

              <!-- 消息体 4: 文件编辑 diff 对比卡片 -->
              <div v-else-if="item.type === 'FILE_EDIT'" class="tool-event">
                <div class="tool-card diff-card" :class="{ open: item.open }">
                  <button class="tool-head" @click="toggleEditDiff(item)">
                    <span class="tool-icon" aria-hidden="true"><ToolIcon kind="edit" :status="item.loading ? 'running' : 'done'" size="14" /></span>
                    <!-- 去掉"代码修改"字样，直接展示修改的文件名与行数增删统计 -->
                    <span class="tool-file" :title="item.filePath">{{ fileNameOf(item.filePath) }}</span>
                    <span class="tool-lines">
                      <em class="plus">+{{ item.plusLines ?? 0 }}</em>
                      <em class="minus">-{{ item.minusLines ?? 0 }}</em>
                    </span>
                    <span class="tool-status done">
                      {{ item.decision === 'REJECTED' ? '已撤销' : item.decision === 'ACCEPTED' ? '已保留' : '已应用待决' }}
                    </span>
                    <span class="tool-chevron" :class="{ rotated: item.open }" aria-hidden="true">
                      <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"/></svg>
                    </span>
                  </button>
                  <div class="accordion-collapse" :class="{ open: item.open }">
                    <div class="accordion-content">
                      <div class="tool-body">
                        <div class="tool-body-inner">
                          <div class="tool-section diff-section">
                            <div class="tool-section-title">变更详情对比 (Diff)</div>
                            <div v-if="item.open && item.loading && !item.loaded" class="diff-loading">正在拉取差异…</div>
                            <FileDiff
                              v-else-if="item.open && item.loaded"
                              :file-path="item.filePath"
                              :old-content="item.decision === 'REJECTED' ? item.newContent : item.oldContent"
                              :new-content="item.newContent"
                            />
                          </div>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
              </div>

              <!-- 消息体 8: 本轮执行完成统计卡片 -->
              <div v-else-if="item.type === 'EXECUTION_COMPLETED'" class="done-card">
                <div class="done-check" aria-hidden="true">
                  <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>
                </div>
                <div class="done-info">
                  <span class="done-title">本轮任务已全部完成</span>
                  <span v-if="item.executionId" class="done-id">ID: {{ item.executionId }}</span>
                </div>
                <div v-if="item.tokens" class="token-stats">
                  <span v-if="typeof item.tokens.input === 'number'" class="token-chip token-input">输入 {{ item.tokens.input }}</span>
                  <span v-if="typeof item.tokens.output === 'number'" class="token-chip token-output">输出 {{ item.tokens.output }}</span>
                  <span v-if="typeof item.tokens.total === 'number'" class="token-chip token-total">Tokens {{ item.tokens.total }}</span>
                </div>
              </div>

              <!-- 消息体 9: 本轮执行被取消 -->
              <div v-else-if="item.type === 'EXECUTION_CANCELLED'" class="done-card cancelled">
                <div class="done-check" aria-hidden="true">
                  <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><line x1="4.93" y1="4.93" x2="19.07" y2="19.07"/></svg>
                </div>
                <div class="done-info">
                  <span class="done-title">本轮执行已停止</span>
                  <span v-if="item.executionId" class="done-id">ID: {{ item.executionId }}</span>
                </div>
              </div>

              <!-- 其余通用日志行 -->
              <div v-else class="event-line" :class="item.type.toLowerCase()">
                <span class="ev-tag">{{ eventTypeLabels[item.type] || item.type }}</span>
                <span class="ev-text">{{ item.text }}</span>
                <span v-if="item.loading" class="event-loading" aria-label="处理中">
                  <i></i><i></i><i></i>
                </span>
              </div>
            </template>

            <!-- 发送中等待动效 -->
            <div v-if="sending" class="row row-ai">
              <div class="ai-body">
                <div class="bubble bubble-ai-thinking">
                  <span class="ai-badge-icon">LX</span>
                  <div class="thinking-dots"><span></span><span></span><span></span></div>
                </div>
              </div>
            </div>
          </div>

          <!-- 底部悬浮输入岛 (Input Island) -->
          <div class="input-area" ref="inputAreaEl">
            <!-- 输入岛上部工具条：模式切换、上拉抽屉、上下文环 -->
            <div class="input-tools">
              <!-- 上拉框 1: 命令审批(ack)模式 -->
              <div class="ack-picker" ref="ackPickerEl">
                <button
                  type="button"
                  class="ack-trigger"
                  :class="{ open: ackOpen }"
                  :title="`${currentAck.label}：${currentAck.desc}`"
                  @click.stop="ackOpen = !ackOpen"
                >
                  <span class="ack-trigger-icon" aria-hidden="true">ACK</span>
                  <span class="ack-trigger-text">{{ currentAck.short }}</span>
                  <svg class="chevron-svg" :class="{ rotated: ackOpen }" viewBox="0 0 24 24" width="11" height="11" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"/></svg>
                </button>
                <transition name="dock-pop">
                  <div v-if="ackOpen" class="ack-menu">
                    <div class="ack-menu-title">命令执行确认级别</div>
                    <button
                      v-for="m in ACK_MODES"
                      :key="m.value"
                      type="button"
                      class="ack-option"
                      :class="{ active: m.value === ackMode }"
                      @click="setAckMode(m.value)"
                    >
                      <span class="ack-option-main">
                        <span class="ack-option-label">{{ m.label }}</span>
                        <span class="ack-option-value">{{ m.desc }}</span>
                      </span>
                      <span v-if="m.value === ackMode" class="ack-check" aria-hidden="true">✓</span>
                    </button>
                  </div>
                </transition>
              </div>

              <!-- 上拉抽屉 2: 待审阅文件抽屉 (Docks) -->
              <div class="docks">
                <div class="dock dock-file" :class="{ 'dock-open': fileDockOpen }">
                  <button type="button" class="dock-head" @click="fileDockOpen = !fileDockOpen" :aria-expanded="fileDockOpen">
                    <span class="dock-title">变更文件</span>
                    <span class="dock-count">({{ pendingEdits.length }})</span>
                    <svg class="chevron-svg" :class="{ rotated: fileDockOpen }" viewBox="0 0 24 24" width="11" height="11" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"/></svg>
                  </button>
                  <!-- 展开后的待审阅文件列表抽屉 -->
                  <Teleport :to="inputAreaEl" :disabled="!fileDockOpen">
                    <transition name="dock-pop">
                      <div v-if="fileDockOpen" class="dock-body dock-body-wide">
                        <div v-if="pendingEdits.length" class="pending-list" :class="{ 'has-open': dockExpanded.size > 0 }">
                          <div v-for="item in pendingEdits" :key="item.recordId" class="pending-item" :class="{ open: dockExpanded.has(item.recordId) }">
                            <div class="pending-row">
                              <button
                                type="button"
                                class="pending-toggle"
                                :title="dockExpanded.has(item.recordId) ? '收起 diff' : '查看 diff'"
                                @click="toggleDockEdit(item)"
                              >
                                <svg class="pending-chevron chevron-svg" :class="{ rotated: dockExpanded.has(item.recordId) }" viewBox="0 0 24 24" width="11" height="11" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"/></svg>
                                <span class="pending-file" :title="item.filePath">
                                  {{ fileNameOf(item.filePath) }}
                                  <span v-if="item.version" class="pending-ver">v{{ item.version }}</span>
                                </span>
                              </button>
                              <span class="pending-lines">
                                <em class="plus">+{{ item.plusLines ?? 0 }}</em>
                                <em class="minus">-{{ item.minusLines ?? 0 }}</em>
                              </span>
                              <div class="decision-actions">
                                <button class="decision-btn keep" :disabled="item.deciding" @click="decideEdit(item, true)">保留</button>
                                <button class="decision-btn undo" :disabled="item.deciding" @click="decideEdit(item, false)">撤销</button>
                              </div>
                            </div>
                            <div v-if="dockExpanded.has(item.recordId)" class="pending-diff">
                              <div v-if="item.loading" class="diff-loading">加载 diff 中…</div>
                              <FileDiff
                                v-else-if="item.loaded"
                                :file-path="item.filePath"
                                :old-content="item.oldContent"
                                :new-content="item.newContent"
                              />
                              <div v-else class="diff-loading">无法加载内容</div>
                            </div>
                          </div>
                          <div class="pending-footer">
                            <button class="decision-btn keep" @click="decideAllPending(true)">全部保留</button>
                            <button class="decision-btn undo" @click="decideAllPending(false)">全部撤销</button>
                          </div>
                        </div>
                        <div v-else class="dock-empty">暂无待审阅的文件变更</div>
                      </div>
                    </transition>
                  </Teleport>
                </div>
              </div>

              <!-- 上下文用量环形图 -->
              <ContextRing
                class="ctx-ring-slot"
                :ratio="ctxRatio"
                :token-count="ctxTokens"
                :max-tokens="ctxMax"
              />
            </div>

            <!-- 输入框主体与微交互按钮 -->
            <div class="input-box" :class="{ focused: inputFocused }">
              <textarea
                v-model="input"
                rows="1"
                placeholder="向 LingXi 提出任何编程任务、代码分析或指令..."
                :disabled="sending"
                @keydown.enter.exact.prevent="sendMessage"
                @focus="inputFocused = true"
                @blur="inputFocused = false"
              ></textarea>
              
              <!-- 发送 / 停止微交互按钮 -->
              <button
                v-if="executing"
                class="stop-btn"
                :disabled="ctlBusy"
                title="停止执行"
                @click="stopAgent"
              >
                <svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor"><rect x="5" y="5" width="14" height="14" rx="3"/></svg>
              </button>
              <button
                v-else
                class="send-btn"
                :disabled="sending || !input.trim()"
                @click="sendMessage"
                title="发送消息 (Enter)"
              >
                <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                  <line x1="12" y1="19" x2="12" y2="5"/>
                  <polyline points="5 12 12 5 19 12"/>
                </svg>
              </button>
            </div>
            <p class="tips">LingXi 智能体驱动 · 所有代码修改可随时一键审查回退</p>
          </div>
        </div>
      </div>
    </main>

    <!-- 工作区选择模态窗 -->
    <transition name="fade">
      <div v-if="dirPickerOpen" class="picker-overlay" @click.self="dirPickerOpen = false">
        <div class="picker-modal">
          <div class="picker-head">
            <span class="picker-title">选择工作区宿主目录</span>
            <button type="button" class="picker-close" :disabled="pickerSelecting" @click="dirPickerOpen = false" aria-label="关闭">✕</button>
          </div>
          <div class="picker-nav">
            <button type="button" class="picker-up" :disabled="pickerLoading || pickerSelecting" @click="pickerGoUp" title="上一级">
              <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="19" x2="12" y2="5"/><polyline points="5 12 12 5 19 12"/></svg>
              <span>上一级</span>
            </button>
            <span class="picker-path" :title="pickerPath">{{ pickerPath || '（磁盘根视图）' }}</span>
          </div>
          <div class="picker-body">
            <div v-if="pickerLoading" class="picker-state">
              <span class="spin-dot"></span> 正在扫描目录...
            </div>
            <div v-else-if="pickerDirs.length" class="picker-dirs">
              <button
                v-for="(dir, di) in pickerDirs"
                :key="dir.path || di"
                type="button"
                class="picker-dir"
                @click="pickerEnterDir(dir)"
              >
                <span class="picker-dir-icon" aria-hidden="true">
                  <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>
                </span>
                <span class="picker-dir-name" :title="dir.path">{{ dir.name }}</span>
                <span class="picker-dir-arrow" aria-hidden="true">›</span>
              </button>
            </div>
            <div v-else class="picker-state">{{ pickerError || '无子文件夹' }}</div>
          </div>
          <div class="picker-foot">
            <button type="button" class="workdir-btn" :disabled="pickerSelecting" @click="dirPickerOpen = false">取消</button>
            <button
              type="button"
              class="workdir-btn primary"
              :disabled="pickerSelecting || !pickerPath"
              @click="pickerSelectCurrent"
            >
              {{ pickerSelecting ? '切换中…' : '选定当前文件夹' }}
            </button>
          </div>
        </div>
      </div>
    </transition>
  </div>
</template>

<style scoped src="./HomeView.css"></style>
