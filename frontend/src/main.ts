import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import './styles/index.css'
import App from './App.vue'
import router from './router'
import { i18n } from './i18n'
import { setupMobileTableCards } from './utils/mobileTableCards'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(i18n)
app.use(ElementPlus)

// 全局增强放在挂载之前：即使某个组件渲染报错，也不影响它们生效
// 1) 移动端把宽表格渲染成卡片列表（桌面端不受影响）
setupMobileTableCards()
// 2) PWA：生产构建注册 Service Worker（开发环境不注册，避免干扰 HMR）
if (import.meta.env.PROD && 'serviceWorker' in navigator) {
  navigator.serviceWorker.register('/sw.js').catch(() => {
    // 非 HTTPS / 浏览器限制等情况下静默降级
  })
}

app.mount('#app')
