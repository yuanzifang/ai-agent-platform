import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '../stores/user'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('../views/LoginView.vue') },
    { path: '/widget', component: () => import('../views/WidgetView.vue') },
    { path: '/share/:token', component: () => import('../views/ShareView.vue') },
    { path: '/sso/callback', component: () => import('../views/SsoCallbackView.vue') },
    { path: '/reset-password', component: () => import('../views/ResetPasswordView.vue') },
    {
      path: '/',
      component: () => import('../views/BasicLayout.vue'),
      children: [
        { path: '', redirect: '/agents' },
        { path: 'agents', component: () => import('../views/AgentListView.vue') },
        { path: 'memories', component: () => import('../views/MemoryView.vue') },
        { path: 'agent/edit/:id', component: () => import('../views/AgentEditView.vue') },
        { path: 'knowledge', component: () => import('../views/KnowledgeListView.vue') },
        { path: 'skills', component: () => import('../views/SkillListView.vue') },
        { path: 'tools', component: () => import('../views/ToolListView.vue') },
        { path: 'prompt-templates', component: () => import('../views/PromptTemplateListView.vue') },
        { path: 'mcp-servers', component: () => import('../views/McpServerListView.vue') },
        { path: 'templates', component: () => import('../views/AgentTemplateView.vue') },
        { path: 'square', component: () => import('../views/AgentSquareView.vue') },
        { path: 'knowledge/:id', component: () => import('../views/KnowledgeDetailView.vue') },
        { path: 'settings/usage', component: () => import('../views/UsageView.vue') },
        { path: 'billing', component: () => import('../views/BillingView.vue') },
        { path: 'provider', component: () => import('../views/ProviderAdminView.vue') },
        { path: 'admin/users', component: () => import('../views/UserAdminView.vue') },
        { path: 'admin/tenant', component: () => import('../views/TenantAdminView.vue') },
        { path: 'admin/platform', component: () => import('../views/PlatformAdminView.vue') },
        { path: 'observability/:agentId?', component: () => import('../views/ObservabilityView.vue') },
        { path: 'metrics/:agentId?', component: () => import('../views/MetricsView.vue') },
        { path: 'audit', component: () => import('../views/AuditView.vue') },
        { path: 'admin/settings', component: () => import('../views/SysConfigView.vue') },
        { path: 'eval/:agentId?', component: () => import('../views/EvalView.vue') },
        { path: 'cases', component: () => import('../views/CaseLibraryView.vue') },
        { path: 'approvals', component: () => import('../views/ApprovalView.vue') },
        { path: 'chat/:agentId', component: () => import('../views/ChatView.vue') }
      ]
    },
    { path: '/:pathMatch(.*)*', component: () => import('../views/NotFoundView.vue') }
  ]
})

// 路由守卫：先查登录态（需求文档 4.4；租户配置加载随 M2 租户模块接入）
router.beforeEach(async (to) => {
  const user = useUserStore()
  // 公开路由：登录、SSO 回调、会话分享
  if (to.path === '/login' || to.path === '/sso/callback' || to.path === '/reset-password'
      || to.path === '/widget' || to.path.startsWith('/share/')) return
  // 页面刷新/新标签页：先用 HttpOnly Cookie 尝试换取 access token
  if (!user.accessToken && !user.bootstrapped) {
    await user.bootstrap()
  }
  if (!user.accessToken) {
    return '/login'
  }
})

export default router
