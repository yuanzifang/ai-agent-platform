import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 后端地址可用 VITE_API_TARGET 覆盖（默认本地 8080；联调第二实例时很有用）
const apiTarget = process.env.VITE_API_TARGET || 'http://localhost:8080'

export default defineConfig({
  plugins: [vue()],
  server: {
    // 监听所有网卡，方便通过 Tailscale / 局域网 IP 访问（本机仍用 localhost:5173）
    host: '0.0.0.0',
    port: 5173,
    // Vite 默认只放行 localhost 和 IP，用 MagicDNS 域名访问时需要显式允许
    allowedHosts: ['.ts.net'],
    proxy: {
      '/api': { target: apiTarget, changeOrigin: true },
      // 精确匹配 /mcp，避免把前端路由 /mcp-servers 也代理到后端
      '^/mcp$': { target: apiTarget, changeOrigin: true }
    }
  },
  build: {
    rolldownOptions: {
      output: {
        // 按依赖域拆包：让 ECharts/Markdown 随相关页面按需加载，公共依赖单独缓存。
        // 注意：不要给分组加 maxSize —— Rolldown 会按体积再切分共享模块，
        // 导致 chunk 初始化顺序错乱（生产构建白屏：isSymbol is not a function 之类）。
        codeSplitting: {
          groups: [
            {
              name: 'element-plus',
              test: /node_modules[\\/]element-plus[\\/]/,
              priority: 30
            },
            {
              name: 'echarts',
              test: /node_modules[\\/](echarts|zrender)[\\/]/,
              priority: 25
            },
            {
              name: 'markdown',
              test: /node_modules[\\/](marked|dompurify|highlight\.js)[\\/]/,
              priority: 20
            },
            {
              name: 'vue-vendor',
              test: /node_modules[\\/](@vue|@intlify|vue-router|vue-i18n|pinia|vue)[\\/]/,
              priority: 15
            },
            {
              name: 'vendor',
              test: /node_modules[\\/]/,
              priority: 1,
              minSize: 30_000
            }
          ]
        }
      }
    }
  }
})
