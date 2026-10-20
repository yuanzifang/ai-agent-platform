import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      // 精确匹配 /mcp，避免把前端路由 /mcp-servers 也代理到后端
      '^/mcp$': { target: 'http://localhost:8080', changeOrigin: true }
    }
  }
})
