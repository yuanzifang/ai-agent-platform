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
  },
  build: {
    rolldownOptions: {
      output: {
        // 按依赖域拆包：减少首屏主包体积，并让 ECharts/Markdown 随相关页面按需加载。
        // maxSize 让 Element Plus/ECharts 等大依赖继续切成 <500KB 的 chunk，避免单包过大。
        codeSplitting: {
          groups: [
            {
              name: 'element-plus',
              test: /node_modules[\\/]element-plus[\\/]/,
              priority: 30,
              maxSize: 420_000
            },
            {
              name: 'echarts',
              test: /node_modules[\\/](echarts|zrender)[\\/]/,
              priority: 25,
              maxSize: 420_000
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
