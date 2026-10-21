import { createI18n } from 'vue-i18n'

const messages = {
  'zh-CN': {
    app: { name: 'AI Agent 平台' },
    menu: {
      agents: 'Agent 列表', memories: '长期记忆', cases: '用例库', square: 'Agent 广场',
      templates: '模板库', knowledge: '知识库', skills: '技能管理', tools: '工具管理',
      mcp: 'MCP Server', approvals: '审批箱', prompts: 'Prompt 模板', provider: '模型接入',
      usage: '用量看板', billing: '计费管理', users: '成员管理', tenant: '租户设置',
      audit: '审计日志', platform: '平台管理', settings: '系统设置'
    },
    common: {
      user: '用户', notifications: '通知', markAllRead: '全部已读', noNotifications: '暂无通知',
      security: '安全设置', logout: '退出登录', language: '语言', chinese: '中文', english: 'English'
    },
    login: {
      username: '用户名', password: '密码', tenant: '租户标识（可选）', login: '登 录',
      sso: '企业 SSO 登录', forgot: '忘记密码？', mfa: '需要二次验证', code: '验证码',
      verify: '验 证', back: '返回登录', invite: '邀请激活',
      inviteDesc: '你被邀请加入团队，设置用户名和密码完成激活', activate: '激活并加入'
    }
  },
  'en-US': {
    app: { name: 'AI Agent Platform' },
    menu: {
      agents: 'Agents', memories: 'Long-term Memory', cases: 'Case Library', square: 'Agent Square',
      templates: 'Templates', knowledge: 'Knowledge Base', skills: 'Skills', tools: 'Tools',
      mcp: 'MCP Server', approvals: 'Approvals', prompts: 'Prompt Templates', provider: 'Model Providers',
      usage: 'Usage', billing: 'Billing', users: 'Members', tenant: 'Tenant Settings',
      audit: 'Audit Log', platform: 'Platform Admin', settings: 'System Settings'
    },
    common: {
      user: 'User', notifications: 'Notifications', markAllRead: 'Mark all read', noNotifications: 'No notifications',
      security: 'Security', logout: 'Sign out', language: 'Language', chinese: '中文', english: 'English'
    },
    login: {
      username: 'Username', password: 'Password', tenant: 'Tenant key (optional)', login: 'Sign in',
      sso: 'Enterprise SSO', forgot: 'Forgot password?', mfa: 'Two-factor verification', code: 'Code',
      verify: 'Verify', back: 'Back to login', invite: 'Invitation',
      inviteDesc: 'Set a username and password to join the team', activate: 'Activate & join'
    }
  }
}

export type Locale = 'zh-CN' | 'en-US'
const saved = localStorage.getItem('locale')
const locale: Locale = saved === 'en-US' ? 'en-US' : 'zh-CN'

export const i18n = createI18n({
  legacy: false,
  locale,
  fallbackLocale: 'zh-CN',
  messages
})

export function setLocale(next: Locale) {
  i18n.global.locale.value = next
  localStorage.setItem('locale', next)
  document.documentElement.lang = next
}
