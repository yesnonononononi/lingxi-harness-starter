import request from '../utils/request'

/**
 * Workspace API of the backend (/agent/workspace).
 * Responses use the shared { code, message, data } envelope; the request interceptor unwraps data.
 */

// Browse one level of host sub-directories; without a path, return the drive roots
export function listDirs(path) {
  return request.get('/agent/workspace/dirs', { params: path ? { path } : {} })
}

// Select a host folder as the active workspace (docker mode reuses or creates a mounting container)
export function selectWorkspace(path) {
  return request.post('/agent/workspace/select', { path })
}

// Current active workspace state (hostDir / containerId / containerName / workDir / mode)
export function getCurrentWorkspace() {
  return request.get('/agent/workspace/current')
}
