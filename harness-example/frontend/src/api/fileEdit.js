import request from '../utils/request'

/**
 * 文件编辑决策接口:编辑已落盘,这里只做“保留 / 撤销”。
 * 后端约定响应结构 { code, message, data },由 request 拦截器解包。
 */

// 会话内待裁决的文件编辑列表(最新在前由前端排序)
export function listPendingEdits(sessionId) {
  return request.get(`/agent/sessions/${sessionId}/edits`)
}

// 保留单条编辑(纯状态变更,无文件 IO)
export function acceptEdit(sessionId, recordId) {
  return request.post('/agent/edits/accept', { sessionId, recordId })
}

// 撤销单条编辑(真实写盘恢复旧内容 / 删除新建文件)
export function rejectEdit(sessionId, recordId) {
  return request.post('/agent/edits/reject', { sessionId, recordId })
}

// 保留一轮(agentRequest)的全部待裁决编辑
export function acceptTurn(sessionId, turnId) {
  return request.post('/agent/turns/accept', { sessionId, turnId })
}

// 撤销一轮的全部待裁决编辑(按版本号从新到旧回滚)
export function rejectTurn(sessionId, turnId) {
  return request.post('/agent/turns/reject', { sessionId, turnId })
}
