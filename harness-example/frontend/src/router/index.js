import { createRouter, createWebHistory } from 'vue-router'
import { getToken } from '../utils/auth'

// 路由表
const routes = [
  {
    path: '/',
    redirect: '/official',
    meta: { title: '官网首页' },
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/LoginView.vue'),
    meta: { title: '登录', public: true },
  },
  {
    path: '/home',
    name: 'Home',
    component: () => import('../views/HomeView.vue'),
    meta: { title: '首页', public: false },
  },
  {
    path: '/official',
    name: 'OfficialHome',
    component: () => import('../views/HomeView.vue'),
    meta: { title: '官网首页', public: true },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    redirect: '/home',
    meta: { title: '页面不存在', public: true },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

// 全局前置守卫：统一做登录态校验，等价于“路由拦截器”
router.beforeEach((to, from, next) => {
  document.title = to.meta.title ? `${to.meta.title} · LingXi 灵犀` : 'LingXi 灵犀'

  const token = getToken()

  if (!to.meta.public && !token) {
    // 未登录访问受保护页面，重定向到登录页，并携带来源地址
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }

  if (to.path === '/login' && token) {
    // 已登录访问登录页，直接回首页
    next({ path: '/home' })
    return
  }

  next()
})

// 全局后置守卫：路由跳转完成后执行
router.afterEach((to, from) => {
  console.log(`[router] ${from.fullPath || '/'} -> ${to.fullPath}`)
})

export default router
