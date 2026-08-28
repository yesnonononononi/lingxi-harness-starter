import axios from 'axios'
import { getToken, removeToken } from './auth'

// 创建 axios 实例
const request = axios.create({
  // 通过 vite 代理转发到后端，避免跨域
  baseURL: '/api',
  timeout: 15000,
})

// ============ 请求拦截器 ============
// 作用：在请求发出前统一附加 token、请求头等公共配置
request.interceptors.request.use(
  (config) => {
    // 从本地存储读取登录 token
    const token = getToken()
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    // 统一设置请求头
    config.headers['Content-Type'] = 'application/json'
    // 记录请求日志
    console.log(`[request] ${config.method.toUpperCase()} ${config.url}`, config.params || '')
    return config
  },
  (error) => {
    // 请求发送失败
    console.error('[request] error', error)
    return Promise.reject(error)
  }
)

// ============ 响应拦截器 ============
// 作用：统一处理后端返回，解包业务数据、统一错误提示、处理登录过期
request.interceptors.response.use(
  (response) => {
    const res = response.data

    // 约定后端返回结构：{ code, message, data }
    // code === 200 表示成功，直接返回 data
    if (res && typeof res === 'object' && 'code' in res) {
      if (res.code === 200 || res.code === 0) {
        return res.data
      }
      // 业务状态码非成功，统一在这里拦截处理
      if (res.code === 401 || res.code === 403) {
        handleUnauthorized()
      }
      return Promise.reject(new Error(res.message || '请求失败'))
    }

    // 非标准结构，原样返回
    return res
  },
  (error) => {
    const status = error.response?.status
    const message = error.response?.data?.message || error.message

    if (status === 401 || status === 403) {
      handleUnauthorized()
    } else if (status === 404) {
      console.error('[response] 404 接口不存在:', error.config?.url)
    } else if (status >= 500) {
      console.error('[response] 服务器内部错误:', message)
    } else if (error.code === 'ECONNABORTED') {
      console.error('[response] 请求超时，请稍后重试')
    } else if (!error.response) {
      console.error('[response] 网络异常，请检查网络连接:', message)
    }

    return Promise.reject(error)
  }
)

// 登录过期统一处理：清除本地登录态并跳转登录页
function handleUnauthorized() {
  removeToken()
  const current = window.location.pathname + window.location.search
  if (window.location.pathname !== '/login') {
    window.location.href = `/login?redirect=${encodeURIComponent(current)}`
  }
}

export default request
