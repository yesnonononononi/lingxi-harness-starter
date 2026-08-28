import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    // 关闭前端热重载（HMR）
    hot: false,
    // 前端请求 /api 时转发到后端服务，避免跨域
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // 后端接口本身不带 /api 前缀，去掉 /api 前缀
        rewrite: (path) => path.replace(/^\/api/, ''),
      },
    },
  },
})
