import request from '../utils/request'

// 登录
export function loginApi(data) {
  return request.post('/auth/login', data)
}

// 登出
export function logoutApi() {
  return request.post('/auth/logout')
}

// 获取当前用户信息
export function getUserInfoApi() {
  return request.get('/auth/userinfo')
}
