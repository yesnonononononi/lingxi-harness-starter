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
const ackMode = ref(localStorage.getItem(ACK_MODE_KEY) || 'FULL_ACCESS')
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
// ====== 执行模式（craft / plan） ======
// 随 /agent/chat 提交 loopBoundary：craft = 直接执行(EXECUTE，默认)；plan = 先规划后执行(PLANING)
const AGENT_MODE_KEY = 'lingxi_agent_mode'
const AGENT_MODES = [
  { value: 'craft', short: 'craft', desc: '直接执行任务，等价后端 EXECUTE' },
  { value: 'plan', short: 'plan', desc: '先输出实施计划，再执行，等价后端 PLANING' },
]
const agentMode = ref(localStorage.getItem(AGENT_MODE_KEY) || 'craft')
const modeOpen = ref(false)
const modePickerEl = ref(null)
const currentMode = computed(() => AGENT_MODES.find((m) => m.value === agentMode.value) || AGENT_MODES[0])
function setAgentMode(mode) {
  agentMode.value = mode
  modeOpen.value = false
  try {
    localStorage.setItem(AGENT_MODE_KEY, mode)
  } catch {
    // ignore storage errors (e.g. private mode)
  }
}
// ====== 执行控制（暂停/继续/停止，作用于当前会话） ======
const executing = ref(false)
const paused = ref(false)
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
  EXECUTION_CANCELLED: '取消',
  WAIT_COMMAND_CHECK: '命令审批',
  WAIT_USER_CHOICE: '需求确认',
  PLAN_UPDATE: '计划卡片'
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
// 所有仍待裁决的文件编辑(跨轮),「文件列表」抽屉的数据源
const pendingEdits = computed(() =>
  events.value.filter((e) => e.type === 'FILE_EDIT' && e.decision === 'pending')
)

// ====== 顶部抽屉（dock）：当前计划任务 + 待审阅文件 ======
// 两列可独立展开，展开后宽度 = 另一列的 2 倍；agent 尚未产出计划/文件时显示 0/0 与 (0)。
const planDockOpen = ref(false)
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

// 最近一份 PLAN_UPDATE：作为「当前计划」上下文。已决断的计划也会继续展示，只是不再提供决策按钮。
const currentPlanItem = computed(() => {
  for (let i = events.value.length - 1; i >= 0; i--) {
    if (events.value[i].type === 'PLAN_UPDATE') return events.value[i]
  }
  return null
})
const currentPlanTasks = computed(() => currentPlanItem.value?.tasks || [])
const currentPlanProgress = computed(() => {
  const it = currentPlanItem.value
  if (!it) return '0/0'
  if (it.progress) return it.progress
  return `${it.doneTasks || 0}/${it.totalTasks || 0}`
})

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

// ====== 命令审批（工具执行前 ack） ======
// WAIT_COMMAND_CHECK 卡片：批准后 agent 循环被唤醒，命令以同一执行真正运行；
// 拒绝则命令不会执行，agent 带着拒绝原因继续
async function decideCommandAck(item, approve) {
  if (!item.toolExecutionId || item.decision || item.deciding) return
  item.deciding = true
  try {
    await request.post(`/agent/commands/${item.toolExecutionId}/${approve ? 'approve' : 'reject'}`)
    item.decision = approve ? 'ACCEPTED' : 'REJECTED'
  } catch (err) {
    const msg = err?.response?.data?.message || err?.message || err
    appendLog({ time: now(), type: 'ERROR', text: `命令审批失败：${msg}` })
    // 404/409：命令已不存在或已被其他端决断，标记为失效避免重复操作
    if (err?.response?.status === 404 || err?.response?.status === 409) {
      item.decision = 'STALE'
    }
  } finally {
    item.deciding = false
  }
}

// ====== 需求选择（require_choice）======
// WAIT_USER_CHOICE 卡片：模型在执行中发现用户诉求语义模糊、需要用户拍板时，主动
// 提问并给出若干可选方案，agent 循环挂起等待；用户点选后写入决策，循环被唤醒并按所选方案继续
async function decideUserChoice(item, choice) {
  if (!item.toolExecutionId || item.decision || item.deciding) return
  item.deciding = true
  try {
    await request.post(`/agent/choices/${item.toolExecutionId}/decide`, { choice })
    item.choice = choice
    item.decision = 'DECIDED'
  } catch (err) {
    const msg = err?.response?.data?.message || err?.message || err
    appendLog({ time: now(), type: 'ERROR', text: `需求选择失败：${msg}` })
    // 404/409：选择已不存在（超时/会话结束）或已被其他端决断，标记为失效避免重复操作
    if (err?.response?.status === 404 || err?.response?.status === 409) {
      item.decision = 'STALE'
    }
  } finally {
    item.deciding = false
  }
}

// ====== 计划内核卡片（PLANING → 批准 / 重新规划 / 拒绝 → EXECUTE 实施） ======
// 计划由内核工具（create_plan / update_plan / update_task / complete_task）创建与推进，
// 后端每次变更都会广播一条 PLAN_UPDATE（携带整份计划快照）。批准前 agent 挂起等待人工
// 决策：用户可给任务补充「提示（tips，只给 agent 看，不改写模型给出的步骤）」，再批准执行、重新规划或拒绝。
async function approvePlanItem(item) {
  if (!canDecidePlan(item)) return
  item.deciding = true
  try {
    await request.post(planActionUrl(item, 'approve'))
    item.decision = 'ACCEPTED'
    item.folded = true
  } catch (err) {
    handlePlanError(item, err, '批准计划失败')
  } finally {
    item.deciding = false
  }
}

// 重新规划：把修改意见（可选）与当前计划一起交给 agent；agent 回到 PLANING 重做计划，
// 新计划会以新的 PLAN_UPDATE 卡片出现，本卡片收敛为「已发起重新规划」
async function revisePlanItem(item) {
  if (!canDecidePlan(item)) return
  item.deciding = true
  try {
    const message = (item.reviseMessage || '').trim()
    await request.post(planActionUrl(item, 'revise'), message ? { message } : {})
    item.decision = 'REVISED'
    item.folded = true
  } catch (err) {
    handlePlanError(item, err, '重新规划失败')
  } finally {
    item.deciding = false
  }
}

async function rejectPlanItem(item) {
  if (!canDecidePlan(item)) return
  item.deciding = true
  try {
    await request.post(planActionUrl(item, 'reject'), {})
    item.decision = 'REJECTED'
    item.folded = true
  } catch (err) {
    handlePlanError(item, err, '拒绝计划失败')
  } finally {
    item.deciding = false
  }
}

// 只有后端仍在等待决断的草稿计划（门尚未被写入）才能被操作
function canDecidePlan(item) {
  return !!item.planId && !item.decision && !item.deciding
}

// 决断地址优先用后端随事件下发的 URL，缺失时按 planId 兜底拼装
function planActionUrl(item, action) {
  return item[`${action}Url`] || `/agent/plans/${item.planId}/${action}`
}

function handlePlanError(item, err, prefix) {
  const msg = err?.response?.data?.message || err?.message || err
  appendLog({ time: now(), type: 'ERROR', text: `${prefix}：${msg}` })
  // 404/409：计划已不存在（超时/会话结束）或已被其他端决断，标记为失效避免重复操作
  if (err?.response?.status === 404 || err?.response?.status === 409) {
    item.decision = 'STALE'
    item.folded = true
  }
}

const PLAN_STATUS_LABEL = { DRAFT: '草稿', APPROVED: '已批准', EXECUTING: '执行中', DONE: '已完成' }
const TASK_STATUS_LABEL = { TODO: '待开始', DOING: '进行中', BLOCKED: '受阻', DONE: '已完成' }

function normalizePlanTask(task) {
  const status = task?.status || 'TODO'
  return {
    id: task?.id || '',
    title: task?.title || '',
    description: task?.description || '',
    status,
    statusLabel: task?.statusLabel || TASK_STATUS_LABEL[status] || status,
    dependencies: Array.isArray(task?.dependencies) ? task.dependencies : [],
    priority: typeof task?.priority === 'number' ? task.priority : null,
    acceptance: task?.acceptance || '',
    // 用户补充给 agent 的提示（不改写模型给出的步骤本身）
    tips: task?.tips || '',
    // 就地编辑态：提示草稿、保存中、错误提示与「已保存」轻提示
    editing: false,
    draftTips: '',
    saving: false,
    saveError: '',
    savedFlash: false,
    open: false,
  }
}

// 后端整份计划快照 → 卡片；同一任务正在编辑的草稿被保留，避免事件刷新打断用户输入
function applyPlanSnapshot(item, data) {
  if (!data) return
  item.planId = data.id || item.planId
  item.version = data.version || item.version
  item.title = data.title || item.title
  item.summaryMarkdown = data.summaryMarkdown || ''
  item.outline = data.outline || ''
  item.status = data.status || item.status
  item.statusLabel = data.statusLabel || item.statusLabel
  item.doneTasks = typeof data.doneTasks === 'number' ? data.doneTasks : item.doneTasks
  item.totalTasks = typeof data.totalTasks === 'number' ? data.totalTasks : item.totalTasks
  item.progress = data.progress || item.progress
  item.approveUrl = data.approveUrl || ''
  item.reviseUrl = data.reviseUrl || ''
  item.rejectUrl = data.rejectUrl || ''
  if (data.state) item.state = data.state
  // 计划已被决断（可能是本端点击，也可能是其他端 / 等待超时）：卡片同步收敛为结论行
  if (!item.decision) {
    if (data.state === 'APPROVED') { item.decision = 'ACCEPTED'; item.folded = true }
    else if (data.state === 'REVISED') { item.decision = 'REVISED'; item.folded = true }
    else if (data.state === 'REJECTED') { item.decision = 'REJECTED'; item.folded = true }
  }
  if (!Array.isArray(data.tasks)) return
  const previousById = new Map((item.tasks || []).map((task) => [task.id, task]))
  item.tasks = data.tasks
    .map((task) => {
      const draft = previousById.get(task.id) || {}
      return {
        ...normalizePlanTask(task),
        editing: !!draft.editing,
        draftTips: draft.editing ? draft.draftTips || '' : '',
        saving: !!draft.saving,
        open: !!draft.open,
      }
    })
    // 低优先级数值优先展示（与后端 PlanOutline 的排序一致），缺省排在最后
    .sort((a, b) => (a.priority ?? Number.MAX_SAFE_INTEGER) - (b.priority ?? Number.MAX_SAFE_INTEGER))
}

// 一条 PLAN_UPDATE(整份计划快照)：同一 planId 就地合并到已有卡片，避免任务推进过程中堆卡片
function applyPlanUpdate(evt) {
  const data = evt.data || {}
  const planId = data.id || ''
  if (!planId) return null
  const existing = events.value.filter((entry) => entry.type === 'PLAN_UPDATE' && entry.planId === planId).pop()
  if (existing) {
    applyPlanSnapshot(existing, data)
    return existing
  }
  const item = {
    time: now(),
    type: 'PLAN_UPDATE',
    executionId: evt.executionId || '',
    sessionId: evt.sessionId || '',
    planId,
    version: data.version || 1,
    title: '',
    summaryMarkdown: '',
    outline: '',
    status: 'DRAFT',
    statusLabel: '',
    doneTasks: 0,
    totalTasks: 0,
    progress: '0/0',
    tasks: [],
    state: data.state || '',
    decision: '',
    deciding: false,
    reviseOpen: false,
    reviseMessage: '',
    folded: false,
    planTextOpen: false,
  }
  applyPlanSnapshot(item, data)
  events.value.push(item)
  scrollToBottom()
  return item
}

function planTaskOf(item, taskId) {
  return (item.tasks || []).find((task) => task.id === taskId) || null
}

// 批准前（草稿且未决断）才允许编辑任务与决断
function canEditPlanTasks(item) {
  return item.status === 'DRAFT' && !item.decision
}

function startEditPlanTask(task) {
  task.editing = true
  task.saveError = ''
  task.draftTips = task.tips || ''
}

function cancelEditPlanTask(task) {
  task.editing = false
  task.saveError = ''
  task.draftTips = ''
}

// 保存任务提示：只提交用户补充的 tips，不改写模型给出的步骤；
// 携带卡片渲染时的 plan version 做乐观并发校验，版本冲突（409）时回填后端返回的最新计划。
async function savePlanTask(item, task) {
  const taskId = task.id
  if (task.saving || !item.sessionId || !item.planId) return
  task.saving = true
  task.saveError = ''
  try {
    const plan = await request.put(
      `/agent/sessions/${item.sessionId}/plans/${item.planId}/tasks/${taskId}`,
      {
        version: item.version,
        tips: (task.draftTips || '').trim(),
      },
    )
    applyPlanSnapshot(item, plan)
    const saved = planTaskOf(item, taskId)
    if (saved) {
      saved.editing = false
      saved.savedFlash = true
      setTimeout(() => { saved.savedFlash = false }, 2000)
    }
  } catch (err) {
    const body = err?.response?.data
    const msg = body?.message || err?.message || err
    // 先用后端返回的最新计划刷新卡片（版本冲突时内容已被他人变更），再回填错误提示
    if (body?.data?.latest) applyPlanSnapshot(item, body.data.latest)
    const failed = planTaskOf(item, taskId)
    if (failed) failed.saveError = msg
    appendLog({ time: now(), type: 'ERROR', text: `保存任务失败：${msg}` })
  } finally {
    const current = planTaskOf(item, taskId)
    if (current) current.saving = false
    else task.saving = false
  }
}

function planCardIcon(item) {
  if (item.decision === 'ACCEPTED') return 'OK'
  if (item.decision === 'REVISED') return 'REVISE'
  if (item.decision === 'REJECTED') return 'REJECT'
  if (item.decision === 'STALE') return 'STALE'
  if (item.status === 'DONE') return 'DONE'
  if (item.status === 'EXECUTING') return 'RUN'
  return 'PLAN'
}

function planCardTitle(item) {
  if (item.decision === 'ACCEPTED') return '已批准执行'
  if (item.decision === 'REVISED') return '已发起重新规划'
  if (item.decision === 'REJECTED') return '计划已拒绝'
  if (item.decision === 'STALE') return '计划卡片已失效'
  if (item.status === 'EXECUTING') return '计划执行中'
  if (item.status === 'DONE') return '计划已完成'
  return '等待计划批准'
}

function planCardSub(item) {
  if (item.decision === 'ACCEPTED') return `agent 正按计划实施 · ${item.progress} 任务完成`
  if (item.decision === 'REVISED') return 'agent 已带着你的修改意见重新规划，新计划稍后以新卡片给出'
  if (item.decision === 'REJECTED') return '本轮不会实施该计划，可直接发送新指令调整'
  if (item.decision === 'STALE') return '该计划已结束（超时/被其他端处理），此卡片仅作记录'
  if (item.status === 'EXECUTING') return `agent 正在实施该计划 · ${item.progress} 任务完成`
  if (item.status === 'DONE') return `计划已全部完成 · ${item.progress}`
  return 'agent 已产出计划并暂停：可给任务补充提示（tips）随计划交给 agent，再批准执行、重新规划或拒绝'
}

// 计划级状态标签：草稿 / 已批准 / 执行中 / 已完成
function planStatusLabel(item) {
  return item.statusLabel || PLAN_STATUS_LABEL[item.status] || ''
}

function ackTitle(item) {
  if (item.decision === 'ACCEPTED') return '命令已批准'
  if (item.decision === 'REJECTED') return '命令已拒绝'
  if (item.decision === 'STALE') return '审批已失效'
  return '等待命令审批'
}

function ackSub(item) {
  if (item.decision === 'ACCEPTED') return 'agent 将唤醒并重新执行该命令，结果稍后返回'
  if (item.decision === 'REJECTED') return '该命令不会执行，agent 将带着拒绝原因继续'
  if (item.decision === 'STALE') return '该命令已被其他端处理，此卡片仅作记录'
  return 'agent 已暂停，请决定是否允许执行'
}

function choiceIcon(item) {
  if (item.decision === 'DECIDED') return 'OK'
  if (item.decision === 'STALE') return 'STALE'
  return 'ASK'
}

function choiceTitle(item) {
  if (item.decision === 'DECIDED') return '需求已确认'
  if (item.decision === 'STALE') return '该选择已失效'
  return '需要你确认需求'
}

function choiceSub(item) {
  if (item.decision === 'DECIDED') return 'agent 将按你选择的方案继续执行'
  if (item.decision === 'STALE') return '该选择已被其他端处理或本轮执行已结束'
  return 'agent 已暂停，请选择一个方案以继续'
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

// 工具归类：read(读取文件) / edit(修改文件) / command(执行命令) / other，
// 用于选择卡片图标与中文动作名
function toolKind(item) {
  const name = (item.toolName || '').toLowerCase()
  if (name.includes('read')) return 'read'
  if (name.includes('edit') || name.includes('write') || name.includes('update')
    || name.includes('insert') || name.includes('replace')) return 'edit'
  if (name.includes('command') || name.includes('exec') || name.includes('terminal')
    || name.includes('shell') || name.includes('bash')) return 'command'
  return 'other'
}

const TOOL_LABELS = { read: '读取文件', edit: '修改文件', command: '执行命令' }
// 卡片头部动作名：读取文件 / 修改文件 / 执行命令，其余回退到原始工具名
function toolLabel(item) {
  return TOOL_LABELS[toolKind(item)] || item.toolName || '工具调用'
}

// 文件类工具（读取/修改文件）：卡片不展开原始参数/输出，内容由专用视图（文件 diff 卡片）呈现
function isFileTool(item) {
  const kind = toolKind(item)
  return kind === 'read' || kind === 'edit'
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
        // 本轮结束（计划状态最终收敛）：
        // - 已批准且正常收尾(EXECUTION_COMPLETED)的计划 → 已完成
        // - 仍未决断的计划审批卡片 → 失效（其 gate 已被释放，无法再审批）
        // - 批准后中止(FAILED/CANCELLED)的计划停留在“已批准”（后端同理，不前置到已完成）
        const finishedExecutionId = evt.executionId || ''
        events.value.forEach((entry) => {
          if (entry.type !== 'PLAN_UPDATE'
            || (finishedExecutionId && entry.executionId !== finishedExecutionId)) return
          if (!entry.decision) {
            // 仍是草稿且审批门已被释放（等待超时/会话结束）：本卡片失效，不能再决断
            if (entry.status === 'DRAFT') {
              entry.decision = 'STALE'
              entry.folded = true
            }
            return
          }
          if (entry.decision === 'ACCEPTED' && evt.type === 'EXECUTION_COMPLETED') {
            entry.status = 'DONE'
          }
        })
        // 需求选择门（require_choice）随本轮执行结束被释放：仍未决断的选择卡片失效
        events.value.forEach((entry) => {
          if (entry.type !== 'WAIT_USER_CHOICE') return
          if (finishedExecutionId && entry.executionId !== finishedExecutionId) return
          if (!entry.decision) entry.decision = 'STALE'
        })
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
          loaded: true,
          loading: false,
        })
        return
      }
      if (evt.type === 'WAIT_COMMAND_CHECK') {
        // 命令被挂起等待人工审批（execute_command ack）：渲染审批卡片；
        // 批准/拒绝写入决策后 agent 循环线程被唤醒
        appendLog({
          time: now(),
          type: 'WAIT_COMMAND_CHECK',
          executionId: evt.executionId || '',
          toolExecutionId: evt.data?.toolExecutionId || '',
          command: evt.data?.command || '',
          decision: '',
          deciding: false,
        })
        return
      }
      if (evt.type === 'WAIT_USER_CHOICE') {
        // require_choice：模型在执行中主动向用户要一个明确选择（语义模糊/需用户拍板），
        // agent 循环挂起等待；用户点选后写入决策，循环被唤醒并按所选方案继续
        appendLog({
          time: now(),
          type: 'WAIT_USER_CHOICE',
          executionId: evt.executionId || '',
          toolExecutionId: evt.data?.toolExecutionId || '',
          question: evt.data?.question || '',
          choices: Array.isArray(evt.data?.choices) ? evt.data.choices : [],
          decision: '',
          choice: '',
          deciding: false,
        })
        return
      }
      if (evt.type === 'PLAN_UPDATE') {
        // 计划内核（create_plan / update_plan / update_task / complete_task / 审批流转）每次
        // 变更都会广播整份计划快照：同一 planId 就地合并刷新同一张卡片，任务推进不再堆卡片。
        applyPlanUpdate(evt)
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
  // 执行/暂停期间发送位是方形停止按钮，Enter 也不应触发新任务
  if (!text || sending.value || executing.value || paused.value) return

  sending.value = true
  appendLog({ time: now(), type: 'USER', text })
  input.value = ''

  try {
    const body = {
      input: text,
      streaming: true,
      commandConfirmLevel: ackMode.value,
      loopBoundary: agentMode.value === 'plan' ? 'PLANING' : 'EXECUTE',
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
  loadContextUsage()
  appendLog({ time: now(), type: 'SYS', text: '已新建会话，发送消息后将自动创建 sessionId' })
}

async function switchSession(session) {
  if (!session.sessionId || session.sessionId === currentSessionId.value) return
  currentSessionId.value = session.sessionId
  currentSessionName.value = session.sessionName || session.sessionId.slice(0, 8)
  events.value = []
  loadContextUsage()
  // 控制按钮只作用于当前会话：切换后重置执行状态
  executing.value = false
  paused.value = false
  showSessionRename.value = false
  try {
    // 拉取该会话的历史消息，映射为与实时事件相同的气泡模型
    const data = await request.get(`/agent/sessions/${session.sessionId}/messages`)
    // 后端直接返回框架 Message 实体，按实体自带的 type 分类（SYSTEM 不渲染）
    for (const m of data?.messages || []) {
      if (m.type === 'USER') {
        events.value.push({ time: '', type: 'USER', text: m.text || '' })
      } else if (m.type === 'AI') {
        events.value.push({
          time: '', type: 'AGENT_MESSAGE', executionId: '',
          text: m.text || '', thinking: m.thinking || '',
          streaming: false, thinkingOpen: false, closed: true,
        })
      } else if (m.type === 'TOOL') {
        events.value.push({
          time: '', type: 'TOOL_STARTED', executionId: '',
          toolName: m.name || 'tool', args: '',
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
          loaded: false, loading: false,
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
    <!-- 左侧边栏：品牌 + 新建会话(sticky 吸顶) + 会话列表 + 左下角状态卡片（DeepSeek 式布局） -->
    <aside class="sidebar" :class="{ collapsed: !showSessions }">
      <!-- 品牌区（IDE 标题栏） -->
      <div class="sidebar-brand">
        <div class="logo">LX</div>
        <div class="brand-copy">
          <div class="brand-name">lingxi-harness</div>
          <div class="brand-sub">coding agent</div>
        </div>
        <button class="brand-collapse" title="收起侧边栏" @click="showSessions = false">‹</button>
      </div>

      <!-- 资源管理器工具条：新建会话（常驻，不随列表滚动） -->
      <div class="explorer-actions">
        <button class="new-session-btn" @click="newSession">
          <span class="ns-icon" aria-hidden="true">+</span>
          <span>新建会话</span>
        </button>
      </div>

      <!-- 会话区（IDE 资源管理器树） -->
      <div class="session-scroll">
        <div class="side-section-label">
          <span class="caret" aria-hidden="true">▾</span>
          <span class="label-text">sessions</span>
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
            <span class="session-icon" aria-hidden="true">◇</span>
            <span class="session-name">{{ s.sessionName || s.sessionId.slice(0, 8) }}</span>
            <span class="session-id">{{ s.sessionId.slice(0, 6) }}</span>
          </div>
          <div v-if="sessions.length === 0" class="session-empty">
            <span>暂无会话，点击上方「新建会话」开始</span>
          </div>
        </div>
      </div>

      <!-- 底部状态栏（IDE status bar） -->
      <div class="sidebar-foot">
        <div class="statusbar">
          <span class="sb-item">
            <span class="foot-dot" :class="{ online: sseStatus === '已连接' }"></span>
            <span>{{ sseStatus }}</span>
          </span>
          <span class="sb-item">{{ sessions.length }} sessions</span>
          <span class="sb-spacer"></span>
          <span class="sb-user" :title="user.name || '访客'">{{ (user.name || '客').slice(0, 1) }}</span>
          <button class="sb-btn" title="收起侧边栏" @click="showSessions = false">‹</button>
          <button class="sb-btn danger" title="退出登录" @click="handleLogout">退出</button>
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
          <button v-if="currentSessionId" class="rename-btn" @click="startRename" title="重命名会话">重命名</button>
        </template>
        <template v-else>
          <input v-model="renameInput" class="rename-input" placeholder="输入会话名称" @keydown.enter="saveRename" @keydown.esc="cancelRename" />
          <button class="rename-btn primary" @click="saveRename">保存</button>
          <button class="rename-btn" @click="cancelRename">取消</button>
        </template>
      </div>

      <!-- 工作目录栏：宿主工作区 + 容器内工作目录 -->
      <div class="workdir-bar">
        <span class="workdir-label">工作区</span>
        <template v-if="!editingDir">
          <span v-if="workspaceContainer" class="ws-tag" :title="`沙箱容器：${workspaceContainer}`">{{ workspaceContainer }}</span>
          <span v-else-if="workspaceMode === 'local'" class="ws-tag">local</span>
          <span class="workdir-path" :title="dirFullTitle()">{{ displayedDir() || '未设置' }}</span>
          <!-- docker 模式下 workdir 是容器内路径，与左侧宿主目录不同；单独展示，
               否则改完 workdir 后工作区栏看不出任何变化 -->
          <span
            v-if="workspaceContainer && workdir && workdir !== workspaceHost"
            class="ws-tag dir"
            :title="`容器内工作目录：${workdir}`"
          >{{ workdir }}</span>
          <button
            class="workdir-btn primary"
            :disabled="pickerSelecting"
            @click="openDirPicker"
            title="选择宿主机文件夹作为沙箱工作区；docker 模式将复用或新建挂载该目录的容器"
          >选择工作区</button>
          <button class="workdir-btn" @click="startEditDir" title="修改容器内的工作目录">修改</button>
        </template>
        <template v-else>
          <span class="ws-tag edit" :title="workspaceHost">容器内</span>
          <input
            v-model="dirInput"
            class="workdir-input"
            placeholder="请输入容器内工作目录的绝对路径"
            @keydown.enter="saveWorkdir"
            @keydown.esc="cancelEditDir"
          />
          <button class="workdir-btn primary" :disabled="savingDir" @click="saveWorkdir">{{ savingDir ? '保存中...' : '保存' }}</button>
          <button class="workdir-btn" @click="cancelEditDir">取消</button>
        </template>
      </div>

      <!-- 无消息时：欢迎小组件居中展示（带交互动画），输入框随之居于页面中心 -->
      <div v-if="!hasChat" class="empty-hero">
        <WelcomeWidget />
      </div>

      <!-- 消息列表 -->
      <div v-show="hasChat" ref="logRef" class="messages">
        <template v-for="(item, index) in events" :key="index">
          <!-- 用户消息：终端命令行（等宽 + 左侧强调条） -->
          <div v-if="item.type === 'USER'" class="row row-user">
            <div class="bubble bubble-user">
              <span class="prompt-glyph" aria-hidden="true">❯</span>
              <div class="bubble-text">{{ item.text }}</div>
            </div>
          </div>

          <!-- 模型 thinking / 回复 -->
          <div v-else-if="item.type === 'AGENT_MESSAGE'" class="row row-ai">
            <div class="ai-body">
              <div v-if="item.thinking" class="tool-event thinking-event">
                <div class="tool-card thinking-card" :class="{ open: item.thinkingOpen }">
                  <button class="tool-head" @click="toggleThinking(item)">
                    <span class="tool-icon" aria-hidden="true">THINK</span>
                    <span class="tool-name">思考过程</span>
                    <span class="tool-status" :class="item.streaming ? 'running' : 'done'">
                      <template v-if="item.streaming"><i></i><i></i><i></i><em>思考中</em></template>
                      <template v-else>完成</template>
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
                <div class="ide-bar">
                  <span class="ide-bar-name">assistant</span>
                  <span class="ide-bar-tag">markdown</span>
                  <span class="ide-bar-spacer"></span>
                  <span v-if="item.streaming" class="ide-bar-tag streaming">● generating</span>
                  <span v-else class="ide-bar-tag">ready</span>
                </div>
                <pre v-if="item.streaming" class="raw-text">{{ item.text }}</pre>
                <MarkdownContent v-else :text="item.text" />
              </div>
            </div>
          </div>

          <!-- 工具调用卡片：点击头部动态展开/收起，running -> done 状态流转 -->
          <div v-else-if="item.type === 'TOOL_STARTED'" class="tool-event">
            <div class="tool-card" :class="{ open: item.open }">
              <button class="tool-head" :class="toolKind(item)" @click="toggleTool(item)">
                <span class="tool-icon" aria-hidden="true"><ToolIcon :kind="toolKind(item)" :status="item.status" /></span>
                <span class="tool-name">{{ toolLabel(item) }}</span>
                <span v-if="item.fileName" class="tool-file" :title="item.fileName">{{ item.fileName }}<span v-if="item.fileRange" class="tool-range">{{ item.fileRange }}</span></span>
                <span class="tool-status" :class="item.status">
                  <template v-if="item.status === 'running'">
                    <i></i><i></i><i></i><em>执行中</em>
                  </template>
                  <template v-else>完成</template>
                </span>
                <span v-if="!isFileTool(item)" class="tool-chevron" :class="{ rotated: item.open }" aria-hidden="true">▾</span>
              </button>
              <div v-if="!isFileTool(item)" class="tool-body">
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
              <button class="tool-head" @click="toggleEditDiff(item)">
                <span class="tool-icon" aria-hidden="true"><ToolIcon kind="edit" /></span>
                <span class="tool-name">修改文件</span>
                <span class="tool-file" :title="item.filePath">{{ fileNameOf(item.filePath) }}</span>
                <span class="tool-lines">
                  <em class="plus">+{{ item.plusLines ?? 0 }}</em>
                  <em class="minus">-{{ item.minusLines ?? 0 }}</em>
                </span>
                <span class="tool-status done">
                  {{ item.decision === 'REJECTED' ? '已撤销' : item.decision === 'ACCEPTED' ? '已保留' : '已应用' }}
                </span>
                <span class="tool-chevron" :class="{ rotated: item.open }" aria-hidden="true">▾</span>
              </button>
              <div class="tool-body">
                <div class="tool-body-inner">
                  <div class="tool-section diff-section">
                    <div class="tool-section-title">变更对比</div>
                    <div v-if="item.open && item.loading" class="diff-loading">正在加载 diff…</div>
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

          <!-- 命令人工审批卡片：execute_command 等待用户批准/拒绝（ack） -->
          <div v-else-if="item.type === 'WAIT_COMMAND_CHECK'" class="tool-event ack-event">
            <div class="ack-card" :class="{ decided: !!item.decision }">
              <div class="ack-head">
                <span class="ack-icon" aria-hidden="true">{{ item.decision === 'ACCEPTED' ? 'OK' : item.decision === 'REJECTED' ? 'DENY' : 'AUTH' }}</span>
                <div class="ack-info">
                  <span class="ack-title">{{ ackTitle(item) }}</span>
                  <span class="ack-sub">{{ ackSub(item) }}</span>
                </div>
              </div>
              <pre class="ack-command">{{ item.command || '（无命令内容）' }}</pre>
              <div v-if="!item.decision" class="ack-actions">
                <button class="decision-btn keep" :disabled="item.deciding" @click="decideCommandAck(item, true)">批准执行</button>
                <button class="decision-btn undo" :disabled="item.deciding" @click="decideCommandAck(item, false)">拒绝</button>
              </div>
              <div v-else class="ack-result" :class="item.decision">
                {{ item.decision === 'ACCEPTED' ? '已批准，命令将继续执行' : item.decision === 'REJECTED' ? '已拒绝，命令不会执行' : '该命令已被其他端处理' }}
              </div>
            </div>
          </div>

          <!-- 需求选择卡片（WAIT_USER_CHOICE）：require_choice 工具——模型在用户诉求语义模糊、
               需要用户拍板时主动提问并给出候选方案，agent 循环挂起等待；用户点选后循环被唤醒 -->
          <div v-else-if="item.type === 'WAIT_USER_CHOICE'" class="tool-event ack-event">
            <div class="ack-card choice-card" :class="{ decided: !!item.decision }">
              <div class="ack-head">
                <span class="ack-icon" aria-hidden="true">{{ choiceIcon(item) }}</span>
                <div class="ack-info">
                  <span class="ack-title">{{ choiceTitle(item) }}</span>
                  <span class="ack-sub">{{ choiceSub(item) }}</span>
                </div>
              </div>
              <p class="choice-question">{{ item.question || '（模型未提供问题描述）' }}</p>
              <div v-if="!item.decision" class="choice-options">
                <button
                  v-for="(opt, idx) in item.choices"
                  :key="idx"
                  class="choice-btn"
                  :disabled="item.deciding"
                  @click="decideUserChoice(item, opt)"
                >{{ opt }}</button>
                <span v-if="!item.choices.length" class="choice-empty">模型未给出候选方案，请在输入框直接补充说明</span>
              </div>
              <div v-else class="ack-result" :class="item.decision === 'DECIDED' ? 'ACCEPTED' : item.decision">
                {{ item.decision === 'DECIDED' ? `已选择：${item.choice}` : item.decision === 'STALE' ? '该选择已被其他端处理或本轮执行已结束' : '已决断' }}
              </div>
            </div>
          </div>

          <!-- 计划内核卡片（PLAN_UPDATE）：create_plan 产出计划后 agent 挂起等待人工
               「批准执行 / 重新规划 / 拒绝」；批准前可给任务补充「提示（tips）」，随计划一起交给 agent。
               同一 planId 的后续事件（任务推进、计划完成）就地刷新本卡；一经决断收敛为一行结论，
               需要时展开仍可查看完整计划书与任务快照。 -->
          <div v-else-if="item.type === 'PLAN_UPDATE'" class="tool-event ack-event">
            <div class="ack-card plan-card" :class="{ decided: !!item.decision }">
              <div class="ack-head">
                <span class="ack-icon" aria-hidden="true">{{ planCardIcon(item) }}</span>
                <div class="ack-info">
                  <div class="ack-title-line">
                    <span class="ack-title">{{ planCardTitle(item) }}</span>
                    <span class="plan-version-chip">v{{ item.version }}</span>
                    <span v-if="planStatusLabel(item)" class="plan-state-chip" :class="String(item.status || 'draft').toLowerCase()">{{ planStatusLabel(item) }}</span>
                    <span v-if="item.totalTasks" class="plan-progress-chip">{{ item.progress }}</span>
                  </div>
                  <span class="ack-sub">{{ planCardSub(item) }}</span>
                </div>
                <button type="button" class="plan-fold-toggle" @click="item.folded = !item.folded">
                  {{ item.folded ? '展开' : '折叠' }}
                </button>
              </div>

              <template v-if="!item.folded">
                <div v-if="item.title" class="plan-title">{{ item.title }}</div>

                <!-- 计划书：默认最多 6 行，可展开查看完整 Markdown -->
                <template v-if="item.summaryMarkdown">
                  <div class="plan-doc" :class="{ clamped: !item.planTextOpen }">
                    <MarkdownContent :text="item.summaryMarkdown" />
                  </div>
                  <button type="button" class="plan-doc-toggle" @click="item.planTextOpen = !item.planTextOpen">
                    {{ item.planTextOpen ? '收起计划书' : '查看完整计划书' }}
                  </button>
                </template>

                <!-- 任务清单：按优先级排序，状态徽标 + 依赖/优先级标签 -->
                <ul v-if="item.tasks && item.tasks.length" class="plan-tasks">
                  <li v-for="task in item.tasks" :key="task.id" class="plan-task"
                    :class="'status-' + String(task.status || 'TODO').toLowerCase()">
                    <div class="plan-task-row">
                      <span class="plan-task-badge" :class="String(task.status || 'TODO').toLowerCase()">{{ task.statusLabel }}</span>
                      <button type="button" class="plan-task-title" :class="{ done: task.status === 'DONE' }"
                        @click="task.open = !task.open">{{ task.title }}</button>
                      <span v-if="task.priority !== null" class="plan-task-chip">P{{ task.priority }}</span>
                      <span v-for="dep in task.dependencies" :key="dep" class="plan-task-chip dep">依赖 {{ dep }}</span>
                      <button v-if="canEditPlanTasks(item) && !task.editing" type="button" class="plan-task-edit"
                        @click="startEditPlanTask(task)">{{ task.tips ? '修改提示' : '添加提示' }}</button>
                    </div>

                    <!-- 用户补充的提示：只给 agent 加提示信息，不改写模型给出的步骤本身 -->
                    <p v-if="task.tips && !task.editing" class="plan-task-tips">
                      <span class="label">提示</span>{{ task.tips }}
                    </p>

                    <!-- 就地编辑：给 agent 的提示，保存成功绿色轻提示，冲突红色提示并刷新 -->
                    <div v-if="task.editing" class="plan-task-editbox">
                      <label class="plan-task-field">
                        <span>提示（给 agent）</span>
                        <textarea v-model="task.draftTips" rows="3" :disabled="task.saving"
                          placeholder="补充给 agent 的提示信息，例如：编辑后必须执行 npm run build，构建通过才算完成任务"></textarea>
                      </label>
                      <div class="plan-task-actions">
                        <button type="button" class="plan-prompt-btn ok" :disabled="task.saving"
                          @click="savePlanTask(item, task)">{{ task.saving ? '保存中…' : '保存' }}</button>
                        <button type="button" class="plan-prompt-btn" :disabled="task.saving"
                          @click="cancelEditPlanTask(task)">取消</button>
                        <span v-if="task.saveError" class="plan-task-error">{{ task.saveError }}</span>
                        <span v-else-if="task.savedFlash" class="plan-task-saved">已保存</span>
                      </div>
                    </div>

                    <div v-else-if="task.open" class="plan-task-detail">
                      <p v-if="task.description" class="plan-task-desc">{{ task.description }}</p>
                      <p v-if="task.acceptance" class="plan-task-accept">
                        <span class="label">验收</span>{{ task.acceptance }}
                      </p>
                      <p v-if="!task.description && !task.acceptance" class="plan-task-empty">该任务未填写描述与验收标准</p>
                    </div>
                  </li>
                </ul>
                <div v-else class="plan-empty">该计划暂无任务</div>

                <!-- 修改意见：点「重新规划」后展开，随计划一起交给 agent -->
                <div v-if="item.reviseOpen && canEditPlanTasks(item)" class="plan-revise">
                  <textarea v-model="item.reviseMessage" class="plan-revise-input" rows="2"
                    placeholder="修改意见（可选）：例如“任务 2 改用 Maven 构建并补充单测”"></textarea>
                </div>
                <div v-if="canEditPlanTasks(item)" class="ack-actions">
                  <button class="decision-btn keep" :disabled="item.deciding" @click="approvePlanItem(item)">批准执行</button>
                  <button v-if="!item.reviseOpen" class="decision-btn replan" :disabled="item.deciding"
                    @click="item.reviseOpen = true">重新规划</button>
                  <button v-else class="decision-btn replan" :disabled="item.deciding"
                    @click="revisePlanItem(item)">提交重新规划</button>
                  <button class="decision-btn undo" :disabled="item.deciding" @click="rejectPlanItem(item)">拒绝</button>
                </div>
                <div v-else-if="item.status === 'EXECUTING'" class="plan-running">
                  agent 正按计划实施，任务状态会随实施进展实时刷新
                </div>
              </template>
            </div>
          </div>

          <!-- 一轮执行完成：token 统计卡片 -->
          <div v-else-if="item.type === 'EXECUTION_COMPLETED'" class="done-card">
            <span class="done-check" aria-hidden="true">OK</span>
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
            <span class="done-check" aria-hidden="true">CANCEL</span>
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
          <div class="ai-body">
            <div class="bubble bubble-ai thinking"><span></span><span></span><span></span></div>
          </div>
        </div>
      </div>

      <!-- 底部输入框 -->
      <div class="input-area" ref="inputAreaEl">
        <!-- 输入框上方工具条：命令审批 / 执行模式两个按钮，紧贴输入框顶部左对齐 -->
        <div class="input-tools">
          <!-- 命令审批(ack)模式上拉框 -->
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
              <span class="ack-trigger-chevron" :class="{ rotated: ackOpen }" aria-hidden="true">▾</span>
            </button>
            <transition name="ack-pop">
              <div v-if="ackOpen" class="ack-menu">
                <div class="ack-menu-title">命令执行前确认级别</div>
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
                    <span class="ack-option-value">{{ m.value }}</span>
                  </span>
                  <span v-if="m.value === ackMode" class="ack-check" aria-hidden="true">✓</span>
                </button>
                <div class="ack-menu-hint">选择后对后续新一轮对话生效</div>
              </div>
            </transition>
          </div>
          <!-- 执行模式上拉框（craft = 直接执行 / plan = 先规划后执行） -->
          <div class="ack-picker" ref="modePickerEl">
            <button
              type="button"
              class="ack-trigger"
              :class="{ open: modeOpen }"
              :title="`执行模式 ${currentMode.value}：${currentMode.desc}`"
              @click.stop="modeOpen = !modeOpen"
            >
              <span class="ack-trigger-text">{{ currentMode.short }}</span>
              <span class="ack-trigger-chevron" :class="{ rotated: modeOpen }" aria-hidden="true">▾</span>
            </button>
            <transition name="ack-pop">
              <div v-if="modeOpen" class="ack-menu narrow">
                <div class="ack-menu-title">执行模式（对新一轮对话生效）</div>
                <button
                  v-for="m in AGENT_MODES"
                  :key="m.value"
                  type="button"
                  class="ack-option"
                  :class="{ active: m.value === agentMode }"
                  @click="setAgentMode(m.value)"
                >
                  <span class="ack-option-main">
                    <span class="ack-option-label">{{ m.short }}</span>
                    <span class="ack-option-value">{{ m.value === 'plan' ? 'PLANING' : 'EXECUTE' }}</span>
                  </span>
                  <span v-if="m.value === agentMode" class="ack-check" aria-hidden="true">✓</span>
                </button>
                <div class="ack-menu-hint">plan 先规划再执行，craft 直接执行</div>
              </div>
            </transition>
          </div>
          <!-- 抽屉（dock）：当前计划任务 + 待审阅文件，紧贴 plan 上拉框右、宽度直到 ContextRing。
               两列 flex 1:1；展开列 flex-grow:2（宽度为另一列的 2 倍），展开时主体向上浮出面板。 -->
          <div class="docks">
            <div class="dock dock-plan" :class="{ 'dock-open': planDockOpen }">
              <button type="button" class="dock-head" @click="planDockOpen = !planDockOpen"
                :aria-expanded="planDockOpen">
                <span class="dock-title">任务列表</span>
                <span class="dock-count">{{ currentPlanProgress }}</span>
                <span class="dock-chevron" :class="{ rotated: planDockOpen }" aria-hidden="true">▾</span>
              </button>
              <transition name="dock-pop">
                <div v-if="planDockOpen" class="dock-body">
                  <ul v-if="currentPlanTasks.length" class="dock-task-list">
                    <li v-for="t in currentPlanTasks" :key="t.id"
                      class="dock-task" :class="'status-' + String(t.status || 'TODO').toLowerCase()">
                      <span class="dock-task-badge">{{ t.statusLabel }}</span>
                      <span class="dock-task-title" :title="t.title">{{ t.title }}</span>
                      <span v-if="t.priority !== null && t.priority !== undefined" class="dock-task-chip">P{{ t.priority }}</span>
                    </li>
                  </ul>
                  <div v-else class="dock-empty">暂无计划任务</div>
                </div>
              </transition>
            </div>
            <div class="dock dock-file" :class="{ 'dock-open': fileDockOpen }">
              <button type="button" class="dock-head" @click="fileDockOpen = !fileDockOpen"
                :aria-expanded="fileDockOpen">
                <span class="dock-title">文件列表</span>
                <span class="dock-count">({{ pendingEdits.length }})</span>
                <span class="dock-chevron" :class="{ rotated: fileDockOpen }" aria-hidden="true">▾</span>
              </button>
              <!-- 展开后即原来的「待裁决文件」审批框：Teleport 到 .input-area 下作为子元素，
                   让面板宽度撑满 chat-panel 而非被 .dock 容器宽度限制；absolute 锚到 .input-area
                   不会越界到侧边栏，也不会被侧边栏覆盖。 -->
              <Teleport :to="inputAreaEl" :disabled="!fileDockOpen">
                <transition name="dock-pop">
                  <div v-if="fileDockOpen" class="dock-body dock-body-wide">
                    <div v-if="pendingEdits.length" class="pending-list" :class="{ 'has-open': dockExpanded.size > 0 }">
                      <div v-for="item in pendingEdits" :key="item.recordId" class="pending-item" :class="{ open: dockExpanded.has(item.recordId) }">
                        <div class="pending-row">
                          <button
                            type="button"
                            class="pending-toggle"
                            :title="dockExpanded.has(item.recordId) ? '收起 diff' : '点击查看 diff'"
                            @click="toggleDockEdit(item)"
                          >
                            <span class="pending-chevron" :class="{ rotated: dockExpanded.has(item.recordId) }" aria-hidden="true">▾</span>
                            <span class="pending-file" :title="item.filePath">{{ fileNameOf(item.filePath) }}</span>
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
                          <div v-if="item.loading" class="diff-loading">正在加载 diff…</div>
                          <FileDiff
                            v-else-if="item.loaded"
                            :file-path="item.filePath"
                            :old-content="item.oldContent"
                            :new-content="item.newContent"
                          />
                          <div v-else class="diff-loading">无法加载 diff 内容</div>
                        </div>
                      </div>
                      <div class="pending-footer">
                        <button class="decision-btn keep" @click="decideAllPending(true)">全部保留</button>
                        <button class="decision-btn undo" @click="decideAllPending(false)">全部撤销</button>
                      </div>
                    </div>
                    <div v-else class="dock-empty">没有待审阅的文件</div>
                  </div>
                </transition>
              </Teleport>
            </div>
          </div>

          <!-- 上下文用量环形图（右对齐）：挂载拉取 + 压缩完成事件过渡更新 -->
          <ContextRing
            class="ctx-ring-slot"
            :ratio="ctxRatio"
            :token-count="ctxTokens"
            :max-tokens="ctxMax"
          />
        </div>

        <!-- 输入框（两个模式按钮已上移至 .input-tools） -->
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
          <!-- 发送位：执行中直接变成方形蓝色停止按钮，空闲时是发送按钮 -->
          <button
            v-if="executing || paused"
            class="stop-btn"
            :disabled="ctlBusy"
            title="停止执行"
            @click="controlAgent('stop')"
          >
            <span class="stop-btn-glyph" aria-hidden="true"></span>
          </button>
          <button
            v-else
            class="send-btn"
            :disabled="sending || !input.trim()"
            @click="sendMessage"
            title="发送"
          >
            ↵
          </button>
        </div>
        <p class="tips">内容由 AI 生成，请仔细甄别</p>
      </div>
        </div>
      </div>
    </main>

    <!-- 选择工作区：宿主目录树弹窗（选中文件夹后后端复用/新建挂载该目录的沙箱容器） -->
    <transition name="fade">
      <div v-if="dirPickerOpen" class="picker-overlay" @click.self="dirPickerOpen = false">
        <div class="picker-modal">
          <div class="picker-head">
            <span class="picker-title">选择工作区目录</span>
            <button type="button" class="picker-close" :disabled="pickerSelecting" @click="dirPickerOpen = false" aria-label="关闭">✕</button>
          </div>
          <div class="picker-nav">
            <button type="button" class="picker-up" :disabled="pickerLoading || pickerSelecting" @click="pickerGoUp" title="上一级">↑ 上一级</button>
            <span class="picker-path" :title="pickerPath">{{ pickerPath || '（选择磁盘 / 根目录）' }}</span>
          </div>
          <div class="picker-body">
            <div v-if="pickerLoading" class="picker-state">加载中…</div>
            <div v-else-if="pickerDirs.length" class="picker-dirs">
              <button
                v-for="(dir, di) in pickerDirs"
                :key="dir.path || di"
                type="button"
                class="picker-dir"
                @click="pickerEnterDir(dir)"
              >
                <span class="picker-dir-icon" aria-hidden="true">▸</span>
                <span class="picker-dir-name" :title="dir.path">{{ dir.name }}</span>
                <span class="picker-dir-arrow" aria-hidden="true">›</span>
              </button>
            </div>
            <div v-else class="picker-state">{{ pickerError || '该目录下没有可进入的子文件夹' }}</div>
          </div>
          <div class="picker-foot">
            <button type="button" class="workdir-btn" :disabled="pickerSelecting" @click="dirPickerOpen = false">取消</button>
            <button
              type="button"
              class="workdir-btn primary"
              :disabled="pickerSelecting || !pickerPath"
              @click="pickerSelectCurrent"
            >
              {{ pickerSelecting ? '切换中…' : '选择当前文件夹' }}
            </button>
          </div>
        </div>
      </div>
    </transition>
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
  flex: 0 0 220px;
  width: 220px;
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  background: #f7f7fa;
  border-right: 1px solid #ececf1;
  transition: margin-left 0.26s cubic-bezier(.4, 0, .2, 1);
}
.sidebar.collapsed { margin-left: -220px; }

/* 品牌区 */
.sidebar-brand {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 12px 8px;
  flex-shrink: 0;
}
.logo {
  flex-shrink: 0;
  width: 32px;
  height: 32px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #4d6bfe, #7a5cff);
  color: #fff;
  font-size: 16px;
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
  font-size: 14px;
  font-weight: 700;
  color: #1f2232;
  letter-spacing: .3px;
}
.brand-sub {
  font-size: 10.5px;
  color: #9a9db0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.brand-collapse {
  flex-shrink: 0;
  width: 22px;
  height: 22px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: #a2a5b8;
  font-size: 15px;
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
  padding: 0 10px;
}
.session-scroll::-webkit-scrollbar { width: 6px; }
.session-scroll::-webkit-scrollbar-thumb { background: #dfe1ea; border-radius: 3px; }
.session-scroll::-webkit-scrollbar-thumb:hover { background: #cfd1dd; }

/* 新建会话：吸顶（会话列表滚动时按钮始终留在顶部） */
.new-session-sticky {
  position: sticky;
  top: 0;
  z-index: 5;
  padding: 6px 0 8px;
  background: linear-gradient(#f7f7fa 82%, rgba(247, 247, 250, 0));
}
.new-session-btn {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 9px 0;
  border: none;
  border-radius: 9px;
  background: linear-gradient(135deg, #4d6bfe, #6a5cff);
  color: #fff;
  font-size: 13px;
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
.ns-icon { font-size: 14px; font-weight: 400; }

/* 会话列表 */
.session-list {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding-bottom: 10px;
}
.session-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 8px;
  border-radius: 9px;
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
.session-icon { flex-shrink: 0; font-size: 12px; opacity: .8; }
.session-name {
  flex: 1;
  min-width: 0;
  font-size: 12px;
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
  gap: 6px;
  padding: 24px 8px;
  text-align: center;
  font-size: 11px;
  line-height: 1.7;
  color: #b3b5c6;
}
.se-icon { font-size: 19px; opacity: .55; }

/* ====== 左下角状态卡片 ====== */
.sidebar-foot {
  flex-shrink: 0;
  padding: 6px 10px 12px;
}
.foot-box {
  display: flex;
  flex-direction: column;
  gap: 7px;
  padding: 8px 10px;
  border: 1px solid #e7e8ee;
  border-radius: 12px;
  background: #ffffff;
  box-shadow: 0 4px 14px rgba(30, 34, 60, .05);
}
.foot-row { display: flex; align-items: center; gap: 7px; min-width: 0; }
.foot-status { font-size: 11px; color: #7d8096; }
.foot-dot {
  flex-shrink: 0;
  width: 7px;
  height: 7px;
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
  width: 24px;
  height: 24px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #4d6bfe, #6a5cff);
  color: #fff;
  font-size: 12px;
  font-weight: 600;
}
.foot-name {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  font-weight: 600;
  color: #262832;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.foot-btn {
  padding: 4px 10px;
  font-size: 11px;
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
  width: 22px;
  padding: 2px 0;
  border: none;
  background: transparent;
  color: #a2a5b8;
  font-size: 14px;
}
.foot-btn.mini:hover { background: #ececf2; color: #4d6bfe; }
.foot-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  border-top: 1px solid #f1f2f6;
  padding-top: 7px;
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
  width: 32px;
  height: 32px;
  border-radius: 9px;
  border: 1px solid #e7e8ee;
  background: #ffffff;
  color: #4d6bfe;
  font-size: 15px;
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
  max-width: 720px;
  margin: 0 auto;
  padding: 14px 0 4px;
  display: flex;
  align-items: center;
  gap: 10px;
}
.session-title {
  flex: 1;
  min-width: 0;
  font-size: 15px;
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
  max-width: 720px;
  margin: 0 auto;
  padding: 2px 0 6px;
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

/* 工作区容器标签（docker：容器名；local：模式名） */
.ws-tag {
  flex-shrink: 0;
  max-width: 150px;
  font-size: 11px;
  line-height: 1;
  padding: 4px 8px;
  border-radius: 6px;
  color: var(--blue);
  background: var(--blue-bg);
  border: 1px solid var(--blue-border);
  font-family: ui-monospace, 'SFMono-Regular', Consolas, monospace;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.ws-tag.edit,
.ws-tag.dir { color: #55586b; background: #f7f8fa; border-color: #eceef4; }

/* ====== 输入框上方的抽屉（dock）：当前计划任务 + 待审阅文件 ======
   紧贴 plan 上拉框右、宽度延伸到 ContextRing 左；flex:1 1 0 平分剩余宽度，
   展开列 flex-grow:2（宽度为另一列的 2 倍）。展开时主体向上浮出面板，避免挤压输入框。 */
.docks {
  flex: 1 1 0;
  min-width: 0;
  display: flex;
  align-items: stretch;
  gap: 8px;
}
.dock {
  position: relative;
  flex: 1 1 0;
  min-width: 0;
  border: 1px solid #e4e6eb;
  border-radius: 8px;
  background: #fff;
  transition: flex-grow 0.22s cubic-bezier(.4, 0, .2, 1), border-color 0.15s ease, box-shadow 0.18s ease;
}
.dock:hover { border-color: #b9c6fb; }
.dock.dock-open { flex-grow: 2; }
.dock.dock-open:hover { box-shadow: 0 2px 8px rgba(30, 34, 60, 0.08); }

.dock-head {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  height: 100%;
  padding: 0 10px;
  border: 0;
  background: transparent;
  cursor: pointer;
  font-family: inherit;
  color: #1e3a8a;
  font-size: 12px;
  font-weight: 600;
  text-align: left;
  border-radius: inherit;
  transition: background 0.15s ease;
}
.dock-head:hover { background: rgba(238, 242, 255, 0.6); }

.dock-title { line-height: 1; flex: none; }
.dock-count {
  margin-left: auto;
  font-variant-numeric: tabular-nums;
  color: #2b6cff;
  font-weight: 700;
  font-size: 12px;
}
.dock-chevron {
  font-size: 10px;
  color: #1e3a8a;
  transition: transform 0.18s ease;
}
.dock-chevron.rotated { transform: rotate(180deg); }

/* 抽屉主体：绝对定位浮在 dock 头上方（向上展开），不挤压输入框 */
.dock-body {
  position: absolute;
  bottom: calc(100% + 6px);
  left: 0;
  right: 0;
  border: 1px solid #eceef4;
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 10px 30px rgba(30, 34, 60, 0.14);
  padding: 6px 10px 10px;
  max-height: 280px;
  overflow-y: auto;
  z-index: 50;
}
.dock-body::-webkit-scrollbar { width: 6px; }
.dock-body::-webkit-scrollbar-thumb { background: #dfe1e8; border-radius: 3px; }
/* 文件列表抽屉主体 = 待裁决审批框：去掉内边距，交给 .pending-list 自己滚动 */
.dock-file .dock-body {
  padding: 0;
  overflow: hidden;
  max-height: none;
}
.dock-file .dock-body .pending-list {
  max-height: min(58vh, 520px);
  border-radius: 12px;
}
/* 文件列表抽屉面板：Teleport 到 .input-area 下作为子元素，
   absolute 锚到 .input-area 顶部之上 6px，宽度撑满 chat-panel（≤1200px 居中）。
   这样既占满主内容区宽度，又不会越界覆盖侧边栏和 plan-dock 头。 */
.dock-file.dock-open .dock-body.dock-body-wide {
  position: absolute;
  left: 0;
  right: 0;
  bottom: calc(100% + 6px);
  width: auto;
  max-width: 1200px;
  margin: 0 auto;
  max-height: calc(100vh - 200px);
}
.dock-empty {
  color: #9ca3af;
  font-size: 12px;
  padding: 10px 4px;
  text-align: center;
}

/* 任务列表（与 .plan-task 视觉一致，但更紧凑） */
.dock-task-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.dock-task {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 8px;
  border: 1px solid #eef1f8;
  border-left: 3px solid #cbd5e1;
  border-radius: 6px;
  background: #fff;
  font-size: 12px;
  transition: box-shadow 0.18s ease;
}
.dock-task:hover { box-shadow: 0 1px 6px rgba(30, 58, 138, 0.06); }
.dock-task.status-todo { border-left-color: #cbd5e1; }
.dock-task.status-doing { border-left-color: #0ea5e9; }
.dock-task.status-blocked { border-left-color: #f59e0b; }
.dock-task.status-done { border-left-color: #16a34a; opacity: 0.72; }
.dock-task-badge {
  flex: none;
  padding: 1px 6px;
  border-radius: 8px;
  font-size: 10.5px;
  font-weight: 600;
  background: #eef2ff;
  color: #1e3a8a;
}
.dock-task-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #262832;
}
.dock-task-chip {
  flex: none;
  font-size: 10.5px;
  color: #6b7280;
  font-variant-numeric: tabular-nums;
}

/* ====== 选择工作区目录树弹窗 ====== */
.fade-enter-active,
.fade-leave-active { transition: opacity .18s ease; }
.fade-enter-from,
.fade-leave-to { opacity: 0; }

.picker-overlay {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(23, 27, 40, .42);
}
.picker-modal {
  display: flex;
  flex-direction: column;
  width: 540px;
  max-width: calc(100vw - 48px);
  height: 480px;
  max-height: calc(100vh - 96px);
  background: #fff;
  border: 1px solid #e2e5ee;
  border-radius: 8px;
  box-shadow: 0 18px 50px rgba(15, 23, 42, .22);
}
.picker-head {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 12px 14px 10px;
  border-bottom: 1px solid #eef0f5;
}
.picker-title { font-size: 14px; font-weight: 600; color: #262832; }
.picker-close {
  border: none;
  background: transparent;
  color: #8a8ca0;
  font-size: 14px;
  width: 26px;
  height: 26px;
  border-radius: 6px;
  cursor: pointer;
  transition: all .18s;
}
.picker-close:hover { background: #eceef4; color: #262832; }
.picker-close:disabled { opacity: .5; cursor: not-allowed; }

.picker-nav {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px 4px;
}
.picker-up {
  flex-shrink: 0;
  font-size: 12px;
  padding: 5px 10px;
  border: 1px solid #e4e6eb;
  border-radius: 6px;
  background: #fff;
  color: #55586b;
  cursor: pointer;
  transition: all .18s;
}
.picker-up:hover:not(:disabled) { border-color: var(--blue); color: var(--blue); }
.picker-up:disabled { opacity: .5; cursor: not-allowed; }
.picker-path {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  font-family: ui-monospace, 'SFMono-Regular', Consolas, monospace;
  color: #55586b;
  background: #f7f8fa;
  border: 1px solid #eceef4;
  border-radius: 6px;
  padding: 6px 10px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.picker-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  margin: 8px 14px;
  border: 1px solid #eef0f5;
  border-radius: 6px;
  background: #fbfcfe;
}
.picker-body::-webkit-scrollbar { width: 6px; }
.picker-body::-webkit-scrollbar-thumb { background: #dfe1ea; border-radius: 3px; }

.picker-state {
  padding: 26px 14px;
  text-align: center;
  font-size: 12px;
  color: #a2a5b8;
}

.picker-dirs { display: flex; flex-direction: column; padding: 6px; gap: 2px; }
.picker-dir {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  cursor: pointer;
  transition: background .15s, border-color .15s;
}
.picker-dir:hover { background: var(--blue-bg); border-color: var(--blue-border); }
.picker-dir-icon { flex-shrink: 0; font-size: 14px; line-height: 1; opacity: .8; }
.picker-dir-name {
  flex: 1;
  min-width: 0;
  text-align: left;
  font-size: 12.5px;
  color: #262832;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.picker-dir-arrow { flex-shrink: 0; color: #a2a5b8; font-size: 15px; }

.picker-foot {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  padding: 10px 14px 14px;
}

/* ====== 消息列表 ====== */
.messages {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 18px 14px 12px;
}
.messages::-webkit-scrollbar { width: 6px; }
.messages::-webkit-scrollbar-thumb { background: #dfe1e8; border-radius: 3px; }

/* 无消息时：欢迎小组件 + 输入框整体居中 */
.empty-hero {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  padding: 24px 16px 40px;
}
.chat-panel.empty .messages { display: none; }

.row { display: flex; gap: 10px; max-width: 720px; margin: 0 auto 18px; }
.row-user { justify-content: flex-end; }

.avatar {
  flex-shrink: 0;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 600;
}
.avatar-user { background: #4d6bfe; color: #fff; order: 2; }
.avatar-ai { background: #ffffff; border: 1px solid #e4e6eb; color: #4d6bfe; }

.ai-body { max-width: calc(100% - 40px); }
.ai-name { font-size: 12px; color: #8a8ca0; margin-bottom: 4px; }

.bubble {
  padding: 10px 14px;
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.6;
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
  max-width: 720px;
  margin: 0 auto 12px;
  font-size: 12.5px;
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
.thinking-text { color: #8a8ca0; font-size: 12.5px; white-space: pre-wrap; word-break: break-word; }

/* ====== 工具调用卡片（动态下拉） ====== */
.tool-event { max-width: 720px; margin: 6px auto; }
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
  padding: 8px 12px;
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
  width: 24px;
  height: 24px;
  border-radius: 8px;
  background: #eef2ff;
  color: #4d6bfe;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
}
/* 工具图标（SVG）：尺寸随 .tool-icon 缩放，颜色继承 currentColor
   注意：SVG 在子组件 ToolIcon 内，需用 :deep 穿透 scoped 样式 */
.tool-icon :deep(svg) { width: 14px; height: 14px; fill: currentColor; display: block; }
.tool-head.read .tool-icon { background: #eef7f3; color: #2f855a; }
.tool-head.edit .tool-icon { background: #fff4e8; color: #d97706; }

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
/* 修改文件卡片的 +新增/-删除 行数统计 */
.tool-lines {
  flex-shrink: 0;
  display: inline-flex;
  gap: 6px;
  font-size: 11px;
  font-style: normal;
}
.tool-lines .plus { font-style: normal; color: #17803d; font-variant-numeric: tabular-nums; }
.tool-lines .minus { font-style: normal; color: #b42318; font-variant-numeric: tabular-nums; }
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
  margin: 0 12px;
}
.tool-section { padding: 8px 0; }
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
  max-width: 720px;
  margin: 14px auto;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
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


/* ====== 发送位的方形停止按钮（执行中取代发送按钮） ====== */
.stop-btn {
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 6px;
  background: var(--blue);
  cursor: pointer;
  transition: all 0.2s;
}
.stop-btn .stop-btn-glyph {
  width: 12px;
  height: 12px;
  border-radius: 2px;
  background: #fff;
}
.stop-btn:hover:not(:disabled) { background: var(--blue-deep); }
.stop-btn:disabled { opacity: 0.6; cursor: not-allowed; }

/* ====== 输入区 ====== */
.input-area {
  position: relative; /* 文件列表抽屉面板的定位锚点（面板 Teleport 到这里） */
  flex-shrink: 0;
  padding: 0 14px 12px;
  background: linear-gradient(to top, #ffffff 70%, rgba(255,255,255,0));
}
/* 输入框上方工具条：与输入框同宽居中，模式按钮左对齐、紧贴输入框顶部 */
.input-tools {
  position: relative;
  z-index: 60; /* 高于文件列表抽屉面板 z-index 50，让 dock-head 在抽屉展开时仍可点击收起 */
  max-width: 720px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 0 6px;
}
/* 上下文用量环形图：贴工具条右侧 */
.ctx-ring-slot { margin-left: auto; }
.input-box {
  max-width: 720px;
  margin: 0 auto;
  display: flex;
  align-items: flex-end;
  gap: 10px;
  padding: 8px 10px 8px 16px;
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
  font-size: 14px;
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

/* 待裁决文件编辑下拉框:位于输入框上方,与输入框同宽居中 */
.pending-edits {
  flex-shrink: 0;
  /* 它是列向 flex 子项：横向 auto margin 会吸收剩余空间，align-items:stretch 因此失效，
     不显式 width:100% 就会被“收缩到内容宽度”，max-width 永远碰不到 */
  width: 100%;
  max-width: 720px;
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
.decision-btn.replan { background: #eef2ff; color: #1d4ed8; }
.decision-btn.replan:hover { background: #e2e8ff; }
.decision-btn:disabled { opacity: .5; cursor: not-allowed; }

/* ====== 命令审批(ack)模式选择器：输入框上方工具条左端上拉框 ====== */
.ack-picker {
  position: relative;
  flex-shrink: 0;
  z-index: 30;
}
.ack-trigger {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 28px;
  padding: 0 8px;
  border: 1px solid #e2e5ec;
  border-radius: 8px;
  background: #fff;
  color: #5a6172;
  font-size: 12px;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.2s;
  white-space: nowrap;
}
.ack-trigger:hover,
.ack-trigger.open {
  border-color: #b9c6fb;
  color: #4d6bfe;
  background: #eef2ff;
}
.ack-trigger-icon { line-height: 1; }
.ack-trigger-text { font-weight: 500; }
.ack-trigger-chevron { color: #9ca3af; font-size: 10px; transition: transform 0.2s; }
.ack-trigger-chevron.rotated { transform: rotate(180deg); }
.ack-menu {
  position: absolute;
  left: 0;
  bottom: calc(100% + 8px);
  width: 272px;
  z-index: 60;
  background: #fff;
  border: 1px solid #eceef4;
  border-radius: 12px;
  box-shadow: 0 10px 30px rgba(30, 34, 60, 0.14);
  padding: 6px;
}
.ack-menu.narrow { width: 236px; }
.ack-menu-title {
  padding: 6px 8px 8px;
  font-size: 11px;
  color: #9ca3af;
  border-bottom: 1px solid #f0f1f5;
  margin-bottom: 4px;
}
.ack-option {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 2px;
  width: 100%;
  text-align: left;
  padding: 8px 30px 8px 10px;
  border: none;
  border-radius: 8px;
  background: transparent;
  cursor: pointer;
  font-family: inherit;
  transition: background 0.15s;
}
.ack-option:hover { background: #f5f7ff; }
.ack-option.active { background: #eef2ff; }
.ack-option-main { display: flex; align-items: baseline; gap: 6px; }
.ack-option-label { font-size: 13px; font-weight: 600; color: #262832; }
.ack-option-value { font-size: 10px; color: #a8abc0; letter-spacing: 0.3px; }
.ack-check {
  position: absolute;
  right: 10px;
  top: 50%;
  transform: translateY(-50%);
  color: #4d6bfe;
  font-weight: 700;
}
.ack-menu-hint {
  padding: 6px 8px 2px;
  font-size: 11px;
  color: #c0c2cf;
  border-top: 1px solid #f0f1f5;
  margin-top: 4px;
}
.ack-pop-enter-active,
.ack-pop-leave-active { transition: opacity 0.12s ease, transform 0.12s ease; }
.ack-pop-enter-from,
.ack-pop-leave-to { opacity: 0; transform: translateY(4px); }

/* dock-pop：抽屉主体向上浮出（translateY(-N) 表示从上方滑入） */
.dock-pop-enter-active,
.dock-pop-leave-active { transition: opacity 0.14s ease, transform 0.14s ease; }
.dock-pop-enter-from,
.dock-pop-leave-to { opacity: 0; transform: translateY(-4px); }

/* ====== 命令审批卡片（消息流中等待批准/拒绝） ====== */
.ack-event { display: flex; }
.ack-card {
  flex: 1;
  min-width: 0;
  border: 1px solid #e8e9f0;
  border-radius: 12px;
  background: #fff;
  padding: 10px 12px;
  box-shadow: 0 2px 10px rgba(30, 34, 60, 0.05);
}
.ack-card.decided { background: #fafbfc; }
.ack-head { display: flex; align-items: center; gap: 10px; }
.ack-icon { font-size: 18px; line-height: 1; }
.ack-info { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.ack-title { font-size: 13px; font-weight: 600; color: #262832; }
.ack-sub { font-size: 12px; color: #9ca3af; }
.ack-command {
  margin: 8px 0 0;
  padding: 6px 9px;
  background: #f4f5f8;
  border: 1px solid #eceef4;
  border-radius: 8px;
  font-size: 12px;
  line-height: 1.5;
  color: #333a4d;
  white-space: pre-wrap;
  word-break: break-all;
  font-family: ui-monospace, SFMono-Regular, Consolas, 'Courier New', monospace;
}
.ack-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 10px;
}
.ack-result { margin-top: 10px; font-size: 12px; }

/* ====== 需求选择(require_choice)卡片：模型提问 + 候选方案按钮 ====== */
.choice-card .choice-question {
  margin: 8px 0 0;
  font-size: 13px;
  color: #262832;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}
.choice-options {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}
.choice-btn {
  border: 1px solid #dbe2f2;
  border-radius: 8px;
  background: #f6f8ff;
  color: #1d4ed8;
  padding: 6px 14px;
  font-size: 12px;
  line-height: 18px;
  cursor: pointer;
  transition: all .2s;
  text-align: left;
}
.choice-btn:hover:not(:disabled) { background: #e8eeff; border-color: #c3cffa; }
.choice-btn:disabled { opacity: .5; cursor: not-allowed; }
.choice-empty { font-size: 12px; color: #9ca3af; }
.ack-result.ACCEPTED { color: #17803d; }
.ack-result.REJECTED { color: #b42318; }
.ack-result.STALE { color: #9ca3af; }

/* ---------- 计划内核卡片（PLAN_UPDATE）：任务清单 + 给 agent 的提示（tips）就地编辑 ---------- */
.plan-card {
  border-color: #c7d4f5;
  background: linear-gradient(180deg, #f8faff, #ffffff);
  animation: planCardIn 0.22s ease-out;
}
.plan-card .plan-title {
  margin: 10px 0 0;
  padding-bottom: 6px;
  font-size: 14px;
  font-weight: 700;
  color: #1e3a8a;
  border-bottom: 1px dashed #dbe3f5;
}
/* 任务清单：按优先级排列，状态徽标 + 依赖/优先级标签 + 给 agent 的提示就地编辑 */
.plan-tasks {
  margin: 8px 0 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 5px;
  max-height: 340px;
  overflow-y: auto;
}
.plan-task {
  border: 1px solid #eef1f8;
  border-left: 3px solid #cbd5e1;
  border-radius: 6px;
  background: #fff;
  transition: border-color 0.18s, box-shadow 0.18s;
}
.plan-task:hover { box-shadow: 0 2px 8px rgba(30, 58, 138, 0.07); }
.plan-task.status-todo { border-left-color: #cbd5e1; }
.plan-task.status-doing { border-left-color: #0ea5e9; }
.plan-task.status-blocked { border-left-color: #f59e0b; }
.plan-task.status-done { border-left-color: #16a34a; }
.plan-task-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
}
.plan-task-badge {
  flex: none;
  padding: 2px 7px;
  border-radius: 9px;
  font-size: 10.5px;
  font-weight: 600;
  line-height: 1.6;
  color: #fff;
  background: #94a3b8;
  transition: background 0.18s;
}
.plan-task-badge.doing { background: #0ea5e9; }
.plan-task-badge.blocked { background: #f59e0b; }
.plan-task-badge.done { background: #16a34a; }
.plan-task-title {
  flex: 1;
  min-width: 0;
  padding: 0;
  font-family: inherit;
  font-size: 12.5px;
  font-weight: 500;
  line-height: 1.6;
  color: #24292f;
  text-align: left;
  border: none;
  background: none;
  cursor: pointer;
  word-break: break-word;
  transition: color 0.15s;
}
.plan-task-title:hover { color: #1d4ed8; }
.plan-task-title.done { color: #94a3b8; text-decoration: line-through; }
.plan-task-chip {
  flex: none;
  padding: 1px 6px;
  font-size: 10.5px;
  line-height: 1.6;
  color: #475569;
  border: 1px solid #dbe3f5;
  border-radius: 9px;
  background: #f8fafc;
}
.plan-task-chip.dep { color: #1d4ed8; border-color: #cfdbf7; background: #f4f7ff; }
.plan-task-edit {
  flex: none;
  padding: 2px 8px;
  font-family: inherit;
  font-size: 11px;
  color: #1d4ed8;
  border: 1px solid #cfdbf7;
  border-radius: 5px;
  background: #fff;
  cursor: pointer;
  transition: background 0.15s;
}
.plan-task-edit:hover { background: #eef3ff; }
/* 用户补充给 agent 的提示：与「验收」明显区分开的标注 */
.plan-task-tips {
  display: flex;
  gap: 6px;
  align-items: flex-start;
  margin: 0 8px 8px;
  padding: 5px 8px;
  font-size: 12px;
  line-height: 1.6;
  color: #7c4a03;
  border: 1px solid #f2ddb4;
  border-left: 3px solid #f59e0b;
  border-radius: 4px;
  background: #fffaf0;
  white-space: pre-wrap;
  word-break: break-word;
}
.plan-task-tips .label {
  flex: none;
  padding: 1px 6px;
  font-size: 10.5px;
  color: #b45309;
  border: 1px solid #f0d3a0;
  border-radius: 9px;
  background: #fff3e0;
}
.plan-task-detail {
  padding: 0 10px 8px;
  font-size: 12px;
  line-height: 1.65;
  color: #475569;
}
.plan-task-desc,
.plan-task-accept { margin: 2px 0; white-space: pre-wrap; word-break: break-word; }
.plan-task-accept .label {
  display: inline-block;
  margin-right: 6px;
  padding: 1px 6px;
  font-size: 10.5px;
  color: #17803d;
  border: 1px solid #b7d7c0;
  border-radius: 9px;
  background: #f2fbf5;
}
.plan-task-empty { margin: 2px 0; color: #94a3b8; }
.plan-task-editbox {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 0 10px 9px;
  animation: planEditIn 0.18s ease-out;
}
.plan-task-field {
  display: flex;
  flex-direction: column;
  gap: 3px;
  font-size: 11.5px;
  color: #475569;
}
.plan-task-field textarea {
  width: 100%;
  box-sizing: border-box;
  padding: 5px 7px;
  font-family: inherit;
  font-size: 12px;
  line-height: 1.55;
  color: #24292f;
  border: 1px solid #dbe3f5;
  border-radius: 6px;
  background: #fff;
  resize: vertical;
}
.plan-task-field textarea:focus { outline: none; border-color: #1d4ed8; }
.plan-task-actions { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.plan-task-error { font-size: 11px; color: #b42318; }
.plan-task-saved { font-size: 11px; color: #17803d; }
.plan-empty { margin-top: 8px; font-size: 12px; color: #94a3b8; }
.plan-running { margin-top: 8px; font-size: 12px; color: #1d4ed8; }

/* 计划书：默认最多约 6 行，展开后完整渲染 Markdown */
.plan-doc {
  margin-top: 8px;
  padding: 8px 10px;
  border: 1px solid #e3e8f4;
  border-radius: 6px;
  background: #fff;
}
.plan-doc .md-content { font-size: 12.5px; line-height: 1.7; }
.plan-doc.clamped {
  max-height: 132px;
  overflow: hidden;
  -webkit-mask-image: linear-gradient(180deg, #000 62%, transparent 100%);
  mask-image: linear-gradient(180deg, #000 62%, transparent 100%);
}
.plan-doc-toggle {
  margin-top: 4px;
  padding: 0;
  font-family: inherit;
  font-size: 11.5px;
  color: #1d4ed8;
  border: none;
  background: none;
  cursor: pointer;
}
.plan-doc-toggle:hover { text-decoration: underline; }

/* 任务动作按钮（保存 / 取消） */
.plan-prompt-btn {
  flex: none;
  padding: 3px 8px;
  font-family: inherit;
  font-size: 11px;
  color: #3b4256;
  border: 1px solid #d6def0;
  border-radius: 5px;
  background: #fff;
  cursor: pointer;
  transition: background 0.15s;
}
.plan-prompt-btn:hover { background: #eef3ff; }
.plan-prompt-btn.ok { border-color: #b7d7c0; color: #17803d; }
.plan-prompt-btn.danger { border-color: #f0c6c2; color: #b42318; }
.plan-prompt-btn:disabled { opacity: 0.5; cursor: not-allowed; }

/* 修改意见输入（仅在「重新规划」时随计划一起交给 agent） */
.plan-revise { margin-top: 8px; }
.plan-revise-input {
  width: 100%;
  box-sizing: border-box;
  padding: 6px 8px;
  font-family: inherit;
  font-size: 12px;
  line-height: 1.5;
  color: #3b4256;
  border: 1px solid #dbe3f5;
  border-radius: 6px;
  background: #fff;
  resize: vertical;
}
.plan-revise-input:focus { outline: none; border-color: #1d4ed8; }

/* 微动效：卡片轻微上浮淡入、任务编辑区展开 */
@keyframes planCardIn {
  from { opacity: 0; transform: translateY(6px); }
  to { opacity: 1; transform: translateY(0); }
}
@keyframes planEditIn {
  from { opacity: 0; transform: translateY(-3px); }
  to { opacity: 1; transform: translateY(0); }
}

/* 计划卡头部附加信息：版本 / 进度 / 折叠 */
.plan-version-chip {
  flex-shrink: 0;
  padding: 2px 7px;
  font-size: 10.5px;
  font-weight: 600;
  line-height: 1.6;
  color: #1e3a8a;
  border: 1px solid #cfdbf7;
  border-radius: 9px;
  background: #f4f7ff;
}
.plan-progress-chip {
  flex-shrink: 0;
  padding: 2px 7px;
  font-size: 10.5px;
  font-weight: 600;
  line-height: 1.6;
  color: #17803d;
  border: 1px solid #b7d7c0;
  border-radius: 9px;
  background: #f2fbf5;
}
.plan-fold-toggle {
  flex: none;
  margin-left: auto;
  padding: 2px 8px;
  font-family: inherit;
  font-size: 11px;
  color: #475569;
  border: 1px solid #d6def0;
  border-radius: 5px;
  background: #fff;
  cursor: pointer;
  transition: background 0.15s;
}
.plan-fold-toggle:hover { background: #eef3ff; }

/* 计划状态标签：草稿 / 已批准 / 执行中 / 已完成 */
.ack-title-line {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.plan-state-chip {
  display: inline-block;
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 600;
  line-height: 1;
  padding: 3px 8px;
  border-radius: 10px;
  color: #fff;
  background: #9ca3af;
}
.plan-state-chip.draft { background: #b45309; }
.plan-state-chip.approved { background: #1d4ed8; }
.plan-state-chip.executing { background: #0ea5e9; }
.plan-state-chip.done { background: #17803d; }

/* 窄屏：任务行纵向堆叠、编辑区满宽 */
@media (max-width: 640px) {
  .plan-task-row { flex-wrap: wrap; }
  .plan-task-title { flex: 1 0 100%; }
  .plan-task-detail,
  .plan-task-editbox { padding-right: 8px; padding-left: 8px; }
}

/* ================================================================
   Coding-IDE 主题覆盖（深蓝主色 · 少圆角 · 消息体面板化）
   置于样式末尾，按 CSS 级联规则覆盖上文同名规则。
   ================================================================ */
.home {
  --blue: #1d4ed8;        /* 主深蓝 */
  --blue-deep: #1e40af;   /* 悬停加深 */
  --blue-ink: #1e3a8a;    /* 更深 */
  --blue-bg: #e8eefc;     /* 浅蓝底 */
  --blue-border: #a9bef0; /* 浅蓝描边 */
  --line: #d7dce6;        /* IDE 分隔线 */
  --ink: #24292f;         /* 正文墨色 */
}

/* ---------- 少圆角：全局方角化 ---------- */
.logo,
.new-session-btn,
.session-item,
.sidebar-expand,
.foot-box,
.rename-btn,
.rename-input,
.workdir-path,
.workdir-input,
.workdir-btn,
.done-card,
.ack-card,
.pending-edits,
.modal,
.import-btn,
.ack-trigger,
.pending-head { border-radius: 6px; }
.brand-collapse,
.tool-icon,
.tool-status,
.ack-command,
.decision-btn,
.token-chip,
.field-input,
.field-textarea { border-radius: 4px; }
.input-box { border-radius: 8px; }
.foot-btn { border-radius: 5px; }
.avatar { border-radius: 6px; }            /* 头像方形化 */
.send-btn,
.stop-btn { border-radius: 6px; }
.empty-icon { border-radius: 8px; }
.bubble { border-radius: 6px; }

/* ---------- 主色：蓝紫 → 深蓝 ---------- */
.logo { background: var(--blue); box-shadow: 0 2px 8px rgba(29, 78, 216, .25); }
.avatar-ai { color: var(--blue); border-color: var(--line); background: #fff; }
.avatar-user { background: var(--blue); }
.new-session-btn { background: var(--blue); box-shadow: 0 3px 8px rgba(29, 78, 216, .22); }
.new-session-btn:hover { filter: brightness(1.08); }
.brand-collapse:hover,
.foot-btn.mini:hover,
.sidebar-expand { color: var(--blue); }
.session-item.active .session-name { color: var(--blue); }
.foot-btn:hover { color: var(--blue); border-color: var(--blue-border); background: var(--blue-bg); }
.foot-avatar { background: var(--blue); }
.rename-btn:hover,
.workdir-btn:hover { color: var(--blue); border-color: var(--blue); }
.rename-btn.primary,
.workdir-btn.primary { background: var(--blue); border-color: var(--blue); }
.rename-btn.primary:hover,
.workdir-btn.primary:hover:not(:disabled),
.send-btn:hover:not(:disabled),
.btn-submit:hover:not(:disabled),
.import-btn:hover { background: var(--blue-deep); }
.rename-input,
.workdir-input { border-color: var(--blue); }
.input-box.focused { border-color: var(--blue); box-shadow: 0 0 0 3px rgba(29, 78, 216, .10); }
.field-input:focus,
.field-textarea:focus { border-color: var(--blue); box-shadow: 0 0 0 3px rgba(29, 78, 216, .10); }
.send-btn,
.btn-submit,
.import-btn,
.empty-icon { background: var(--blue); }
.tool-name,
.tool-chevron.rotated,
.ack-check { color: var(--blue); }
.tool-icon { background: var(--blue-bg); color: var(--blue); }
.tool-range { background: rgba(29, 78, 216, .08); color: var(--blue); }
.tool-card.open { border-color: var(--blue-border); box-shadow: 0 4px 14px rgba(29, 78, 216, .08); }
.tool-status.running,
.ack-option.active { color: var(--blue); background: var(--blue-bg); }
.token-input { color: var(--blue); background: var(--blue-bg); }
.ack-trigger:hover,
.ack-trigger.open { border-color: var(--blue-border); color: var(--blue); background: var(--blue-bg); }
.event-loading i,
.tool-status i { background: currentColor; }
.tool-status.running i { background: var(--blue); }
.event-loading i { background: var(--blue); }

/* ---------- 消息体：IDE 面板化 ---------- */
.avatar {
  font-family: ui-monospace, 'SFMono-Regular', Consolas, monospace;
  font-weight: 600;
}

/* AI 消息：编辑器标签栏 + 白色代码面板 */
.bubble-ai {
  padding: 0;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 6px;
  box-shadow: 0 1px 3px rgba(27, 31, 36, .06);
  overflow: hidden;
}
.bubble-ai.thinking { padding: 8px 14px; }
.ai-name { display: none; }
.ide-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 10px;
  background: #f3f5f8;
  border-bottom: 1px solid var(--line);
  font-family: ui-monospace, 'SFMono-Regular', Consolas, monospace;
  font-size: 11px;
  color: #57606a;
}
.ide-dots { display: inline-flex; gap: 5px; }
.ide-dots i { width: 9px; height: 9px; border-radius: 50%; background: #ff5f56; }
.ide-dots i:nth-child(2) { background: #ffbd2e; }
.ide-dots i:nth-child(3) { background: #27c93f; }
.ide-bar-name { font-weight: 600; color: #3a4454; white-space: nowrap; }
.ide-bar-spacer { flex: 1; }
.ide-bar-tag {
  font-size: 10px;
  color: #6e7681;
  border: 1px solid #d5dbe4;
  background: #fff;
  padding: 1px 6px;
  border-radius: 4px;
  letter-spacing: .5px;
  text-transform: uppercase;
  white-space: nowrap;
}
.ide-bar-tag.streaming { color: var(--blue); border-color: var(--blue-border); background: var(--blue-bg); animation: ide-blink 1.2s ease infinite; }
@keyframes ide-blink { 50% { opacity: .45; } }

.bubble-ai > .md-content,
.bubble-ai > .raw-text { padding: 10px 14px; color: var(--ink); }

/* 用户消息：右侧深蓝命令块（终端式） */
.bubble-user {
  background: var(--blue);
  color: #fff;
  border: none;
  border-top-right-radius: 2px;
  box-shadow: 0 2px 6px rgba(29, 78, 216, .18);
}
.bubble-user .bubble-text { color: #fff; }

/* 工具 / 思考卡片：面板式工具行 */
.tool-card { background: #fbfcfe; }
.tool-head { border-bottom: 1px solid #e6e9f0; background: #f4f6f9; }
.tool-card.open .tool-head { background: #eef1f6; border-bottom: none; }
.tool-head:hover { background: #eceff4; }
.tool-name { font-family: ui-monospace, 'SFMono-Regular', Consolas, monospace; font-weight: 700; }
.thinking-card .tool-icon,
.thinking-card .tool-name { color: #6e7781; }
.thinking-card .tool-icon { background: #e9ecf2; }
.tool-body-inner { background: #fbfcfe; }
.tool-code { font: 12px/1.6 ui-monospace, 'SFMono-Regular', Consolas, monospace; }

/* 事件行小字转等宽，接近终端日志 */
.event-line { font-family: ui-monospace, 'SFMono-Regular', Consolas, monospace; }
/* ================================================================
   IDE 主题（VS Code 浅色风 · 紧凑 · 面板化）
   置于样式末尾，按 CSS 级联规则覆盖上文同名规则。
   重点：左侧「资源管理器」会话列表 + 编辑器风格消息体。
   ================================================================ */
.home {
  --blue: #1d4ed8;        /* 主深蓝 */
  --blue-deep: #1e40af;   /* 加深 */
  --blue-ink: #1e3a8a;    /* 更深 */
  --blue-bg: #e8eefc;     /* 浅蓝底 */
  --blue-border: #a9bef0; /* 浅蓝描边 */
  --line: #d7dce6;        /* IDE 分隔线 */
  --line-soft: #e6e9ef;
  --chrome: #f3f4f6;      /* 侧栏 chrome 灰 */
  --chrome-hover: #e7e9ee;
  --ink: #24292f;         /* 正文墨色 */
  --ink-dim: #57606a;
  --mono: ui-monospace, 'SFMono-Regular', 'JetBrains Mono', Consolas, 'Courier New', monospace;
}

/* ---------- 全局方角化：贴近编辑器（几乎无圆角） ---------- */
.logo, .new-session-btn, .session-item, .sidebar-expand, .rename-btn,
.rename-input, .workdir-path, .workdir-input, .workdir-btn, .done-card, .ack-card,
.pending-edits, .modal, .import-btn, .ack-trigger, .pending-head { border-radius: 4px; }
.brand-collapse, .tool-icon, .tool-status, .ack-command, .decision-btn, .token-chip,
.field-input, .field-textarea { border-radius: 3px; }
.input-box { border-radius: 6px; }
.foot-btn { border-radius: 3px; }
.avatar { border-radius: 4px; }
.send-btn,
.stop-btn { border-radius: 4px; }
.bubble { border-radius: 4px; }

/* ---------- 主色：蓝紫 → 深蓝 ---------- */
.logo { background: var(--blue); box-shadow: none; }
.avatar-ai { color: var(--blue); border-color: var(--line); background: #fff; }
.avatar-user { background: var(--blue); }
.brand-collapse:hover,
.foot-btn.mini:hover,
.sidebar-expand { color: var(--blue); }
.session-item.active .session-name { color: var(--blue-ink); }
.foot-btn:hover { color: var(--blue); border-color: var(--blue-border); background: var(--blue-bg); }
.foot-avatar { background: var(--blue); }
.rename-btn:hover,
.workdir-btn:hover { color: var(--blue); border-color: var(--blue); }
.rename-btn.primary,
.workdir-btn.primary { background: var(--blue); border-color: var(--blue); }
.rename-btn.primary:hover,
.workdir-btn.primary:hover:not(:disabled),
.send-btn:hover:not(:disabled),
.btn-submit:hover:not(:disabled),
.import-btn:hover { background: var(--blue-deep); }
.rename-input,
.workdir-input { border-color: var(--blue); }
.input-box.focused { border-color: var(--blue); box-shadow: 0 0 0 3px rgba(29, 78, 216, .10); }
.field-input:focus,
.field-textarea:focus { border-color: var(--blue); box-shadow: 0 0 0 3px rgba(29, 78, 216, .10); }
.send-btn,
.btn-submit,
.import-btn { background: var(--blue); }
.tool-name,
.tool-chevron.rotated,
.ack-check { color: var(--blue); }
.tool-icon { background: var(--blue-bg); color: var(--blue); }
.tool-range { background: rgba(29, 78, 216, .08); color: var(--blue); }
.tool-status.running,
.ack-option.active { color: var(--blue); background: var(--blue-bg); }
.token-input { color: var(--blue); background: var(--blue-bg); }
.ack-trigger:hover,
.ack-trigger.open { border-color: var(--blue-border); color: var(--blue); background: var(--blue-bg); }
.event-loading i,
.tool-status i { background: currentColor; }
.tool-status.running i { background: var(--blue); }
.event-loading i { background: var(--blue); }

/* ================================================================
   一、左侧边栏 → IDE「资源管理器」
   ================================================================ */
.sidebar {
  flex: 0 0 246px;
  width: 246px;
  background: var(--chrome);
  border-right: 1px solid var(--line);
}
.sidebar.collapsed { margin-left: -246px; }

/* 品牌区：紧凑标题栏 */
.sidebar-brand {
  gap: 8px;
  padding: 10px;
  background: #eceef2;
  border-bottom: 1px solid var(--line);
}
.logo {
  width: 24px;
  height: 24px;
  border-radius: 4px;
  font-size: 11px;
  letter-spacing: .4px;
  font-family: var(--mono);
}
.brand-name { font-size: 12.5px; letter-spacing: 0; font-family: var(--mono); }
.brand-sub { font-size: 10px; letter-spacing: .6px; text-transform: uppercase; }
.brand-collapse { width: 20px; height: 20px; font-size: 14px; }

/* 资源管理器工具条 */
.explorer-actions { flex-shrink: 0; padding: 8px 10px 2px; }
.new-session-btn {
  justify-content: flex-start;
  gap: 7px;
  padding: 6px 10px;
  background: #ffffff;
  border: 1px solid var(--line);
  color: #30363d;
  font-family: var(--mono);
  font-size: 12px;
  box-shadow: none;
}
.new-session-btn:hover {
  background: var(--blue-bg);
  border-color: var(--blue-border);
  color: var(--blue);
  filter: none;
  transform: none;
  box-shadow: none;
}
.ns-icon { color: var(--blue); font-weight: 700; }

/* 会话区 */
.session-scroll { padding: 0 6px; }
.side-section-label {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 6px 4px;
  font-family: var(--mono);
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: .8px;
  color: #6b7280;
  text-transform: uppercase;
}
.side-section-label .caret { font-size: 9px; color: #9aa1ad; }
.side-section-label .label-text { flex: 1; min-width: 0; }
.side-section-label .label-count {
  padding: 0 6px;
  font-size: 10px;
  font-weight: 600;
  color: #6b7280;
  background: #e2e4ea;
  border-radius: 8px;
}
.session-list { gap: 1px; padding-bottom: 10px; }
.session-item {
  gap: 6px;
  padding: 4px 8px;
  border-radius: 3px;
  border-left: 2px solid transparent;
}
.session-item:hover { background: var(--chrome-hover); }
.session-item.active {
  background: #dbe4fb;
  border-color: transparent;
  border-left-color: var(--blue);
  box-shadow: none;
}
.session-icon { flex-shrink: 0; font-size: 9px; color: #9aa1ad; }
.session-item.active .session-icon { color: var(--blue); }
.session-name {
  font-family: var(--mono);
  font-size: 12px;
  color: #30363d;
}
.session-item.active .session-name { color: var(--blue-ink); font-weight: 600; }
.session-id {
  flex-shrink: 0;
  margin-left: auto;
  font-family: var(--mono);
  font-size: 9.5px;
  color: #a8adb8;
}
.session-item.active .session-id { color: var(--blue); }
.session-empty { padding: 18px 8px; font-family: var(--mono); font-size: 11px; }

/* 底部状态栏：IDE 蓝色状态条 */
.sidebar-foot { padding: 0; }
.statusbar {
  display: flex;
  align-items: center;
  gap: 9px;
  height: 26px;
  padding: 0 10px;
  background: var(--blue-deep);
  color: #dce4fb;
  font-family: var(--mono);
  font-size: 10.5px;
}
.statusbar .sb-item { display: inline-flex; align-items: center; gap: 5px; }
.statusbar .foot-dot { width: 7px; height: 7px; box-shadow: none; background: #ff8080; }
.statusbar .foot-dot.online { background: #7ee787; box-shadow: none; }
.statusbar .sb-spacer { flex: 1; }
.statusbar .sb-user {
  width: 16px;
  height: 16px;
  border-radius: 3px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: rgba(255, 255, 255, .18);
  color: #fff;
  font-size: 10px;
  font-weight: 700;
}
.statusbar .sb-btn {
  padding: 0 4px;
  border: none;
  background: transparent;
  color: #cdd7f5;
  font-family: inherit;
  font-size: 12px;
  line-height: 1;
  cursor: pointer;
}
.statusbar .sb-btn:hover { color: #fff; }
.statusbar .sb-btn.danger:hover { color: #ffb4a8; }

/* ================================================================
   二、消息体 → 编辑器工作区
   ================================================================ */
/* 顶部会话头 / 工作区栏 / 消息 / 输入：统一 900px 编辑器宽度 */
.session-header,
.workdir-bar,
.run-controls,
.pending-edits,
.input-tools,
.input-box { max-width: 900px; }

.messages { padding: 18px 14px 14px; }
/* 消息行 / 工具卡 / 日志行 / 完成卡：统一 900px 编辑器宽度，左对齐一致 */
.row { max-width: 900px; margin: 0 auto 10px; }

/* 用户消息：终端命令行（等宽 + 左侧蓝色强调条） */
.bubble-user {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  width: 100%;
  padding: 9px 12px;
  background: #f6f8fa;
  color: var(--ink);
  border: 1px solid var(--line);
  border-left: 3px solid var(--blue);
  border-radius: 4px;
  font-family: var(--mono);
  font-size: 12.5px;
  line-height: 1.6;
  box-shadow: none;
  border-top-right-radius: 4px;
}
.bubble-user .prompt-glyph {
  flex-shrink: 0;
  color: var(--blue);
  font-weight: 700;
  user-select: none;
}
.bubble-user .bubble-text {
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--ink);
}

/* AI 消息：编辑器标签栏 + 内容面板 */
.ai-body { width: 100%; max-width: 100%; }
.ai-name { display: none; }
.bubble-ai {
  padding: 0;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 4px;
  box-shadow: none;
  overflow: hidden;
}
.bubble-ai.thinking { padding: 8px 14px; }
.ide-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 10px;
  background: #eceef2;
  border-bottom: 1px solid var(--line);
  font-family: var(--mono);
  font-size: 11px;
  color: var(--ink-dim);
}
.ide-bar-name { font-weight: 700; color: #3a4454; }
.ide-bar-spacer { flex: 1; }
.ide-bar-tag {
  font-size: 9.5px;
  color: #6e7681;
  border: 1px solid #d5dbe4;
  background: #fff;
  padding: 1px 6px;
  border-radius: 3px;
  letter-spacing: .5px;
  text-transform: uppercase;
  white-space: nowrap;
}
.ide-bar-tag.streaming {
  color: var(--blue);
  border-color: var(--blue-border);
  background: var(--blue-bg);
  animation: ide-blink 1.2s ease infinite;
}
@keyframes ide-blink { 50% { opacity: .45; } }
.bubble-ai > .md-content,
.bubble-ai > .raw-text { padding: 12px 14px; color: var(--ink); }

/* 工具 / 思考卡片：IDE 折叠面板 */
.tool-event {
  max-width: 900px;
  margin: 6px auto;
}
.tool-card { background: #fff; border: 1px solid var(--line); border-radius: 4px; }
.tool-card:hover { border-color: #c3c9d4; }
.tool-card.open { border-color: var(--blue-border); box-shadow: none; }
.tool-head { gap: 8px; padding: 6px 10px; background: #f6f7f9; }
.tool-head:hover { background: #eef0f4; }
.tool-icon {
  width: 20px;
  height: 20px;
  border-radius: 3px;
  font-family: var(--mono);
  font-size: 9.5px;
  font-weight: 700;
}
.tool-name { font-family: var(--mono); font-size: 12px; font-weight: 700; }
.tool-file { font-family: var(--mono); font-size: 11.5px; }
.tool-status { font-family: var(--mono); font-size: 10px; border-radius: 3px; }
.tool-chevron { font-size: 10px; }
.tool-body-inner { margin: 0 10px; }
.tool-code { font-size: 11.5px; }
.tool-section-title { font-family: var(--mono); font-size: 10.5px; }
.thinking-card .tool-icon { background: #e9ecf2; color: #6e7781; }

/* 事件行 → 终端日志行 */
.event-line {
  max-width: 900px;
  margin: 0 auto 8px;
  font-family: var(--mono);
  font-size: 11.5px;
}
.ev-tag { font-family: var(--mono); font-size: 10px; border-radius: 3px; }

/* 完成 / 取消卡片 */
.done-card { max-width: 900px; border-radius: 4px; }
.done-check { border-radius: 4px; width: 22px; height: 22px; font-size: 11px; font-family: var(--mono); }
.done-title, .done-id { font-family: var(--mono); }

/* 命令审批 / 计划卡片：面板化 */
.ack-card { border-radius: 4px; box-shadow: none; }

/* ---------- 待裁决文件编辑：点击条目展开 diff ---------- */
.pending-item { display: block; padding: 0; }
.pending-item.open { background: #f8f9fb; }
.pending-list { max-height: 260px; }
.pending-list.has-open { max-height: min(62vh, 580px); }
.pending-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 3px 12px;
}
/* 行内按钮紧凑化：整行高度由约 41px 收到约 30px（只作用于待裁决条目，不影响其它 decision-btn） */
.pending-row .decision-btn {
  padding: 3px 10px;
  line-height: 16px;
}
.pending-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 1;
  min-width: 0;
  padding: 0;
  border: none;
  background: transparent;
  cursor: pointer;
  text-align: left;
}
.pending-toggle .pending-chevron { font-size: 10px; color: #9aa1ad; transition: transform .2s; }
.pending-toggle .pending-chevron.rotated { transform: rotate(180deg); }
.pending-toggle .pending-file { font-family: var(--mono); font-size: 12px; }
.pending-toggle:hover .pending-file { color: var(--blue); }
/* 待裁决面板是双栏 diff 对比：单独放宽画布（其余区域仍保持 900px 编辑器列），
   同时越过 Monaco 的 inline 回退断点（900px），恢复真正的左右对照视图 */
.pending-edits { max-width: 1200px; }
.pending-diff { padding: 0 12px 10px; }
.pending-diff :deep(.file-diff) { height: 340px; }
.diff-loading {
  padding: 10px 2px;
  font-family: var(--mono);
  font-size: 11.5px;
  color: var(--ink-dim);
}
.ack-icon { font-family: var(--mono); font-size: 10px; font-weight: 700; padding: 3px 6px; border-radius: 3px; background: var(--blue-bg); color: var(--blue); }
.ack-command, .plan-task-title, .plan-task-detail { font-family: var(--mono); }
</style>














