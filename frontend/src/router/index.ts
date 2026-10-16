import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '../stores/user'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('../views/LoginView.vue') },
    { path: '/agents', component: () => import('../views/AgentListView.vue') },
    { path: '/', redirect: '/agents' }
  ]
})

// 路由守卫：先查登录态（需求文档 4.4；租户配置加载随 M2 租户模块接入）
router.beforeEach((to) => {
  const user = useUserStore()
  if (to.path !== '/login' && !user.accessToken) {
    return '/login'
  }
})

export default router
