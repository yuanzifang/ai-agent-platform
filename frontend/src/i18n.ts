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
    agentList: {
      search: '搜索 Agent 名称', fromTemplate: '从模板创建', create: '新建 Agent',
      empty: '还没有 Agent，点击右上角「新建 Agent」创建一个', unnamed: '未命名',
      published: '已发布', draft: '草稿', noDesc: '暂无描述', edit: '编辑', observe: '观测',
      metrics: '监控', chat: '对话', publish: '发布', delete: '删除',
      createTitle: '新建 Agent', name: '名称', namePh: '如：售后客服助手', desc: '描述',
      descPh: '一句话说明这个 Agent 做什么', model: '模型', selectModel: '选择模型',
      prompt: '提示词', promptPh: '系统提示词（System Prompt），定义 Agent 的角色与规则',
      temperature: '温度', cancel: '取消', createPublish: '创建并发布'
    },
    knowledgeList: {
      searchPh: '搜索名称/描述', visibilityPh: '可见范围', private: '只有我',
      tenant: '所有团队成员', partial: '部分成员', search: '搜索', create: '新建知识库',
      empty: '暂无知识库，点击右上角创建', embedding: '嵌入模型', noDesc: '暂无描述',
      docs: '文档', chunks: '分段', creator: '创建人', edit: '编辑', delete: '删除',
      editTitle: '编辑知识库', name: '名称', desc: '描述', model: '嵌入模型',
      modelLocked: '已有文档使用当前模型，不能直接切换', cancel: '取消', save: '保存',
      createTitle: '新建知识库', namePh: '如：产品手册库',
      modelHint: '需先在 Ollama 拉取对应模型；不同维度向量分表存储',
      visibility: '可见范围', createBtn: '创建'
    },
    page: {
      agents: 'Agent 列表', agentEdit: '编辑 Agent', chat: '对话', square: 'Agent 广场',
      templates: '模板库', approvals: '人工审批箱', audit: '审计日志', billing: '计费管理',
      cases: '对话用例库', eval: 'Agent 结果评测', knowledge: '知识库', mcp: 'MCP Server',
      memories: '长期记忆', metrics: '监控统计', observability: '可观测', platform: '平台管理 · 租户',
      prompts: 'Prompt 模板', provider: '模型接入', skills: '技能管理', settings: '系统设置（平台级）',
      tenant: '租户设置', tools: '工具管理', usage: '用量看板', users: '成员管理'
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
    agentList: {
      search: 'Search agents', fromTemplate: 'From template', create: 'New Agent',
      empty: 'No agents yet. Click "New Agent" to create one.', unnamed: 'Untitled',
      published: 'Published', draft: 'Draft', noDesc: 'No description', edit: 'Edit', observe: 'Observe',
      metrics: 'Metrics', chat: 'Chat', publish: 'Publish', delete: 'Delete',
      createTitle: 'New Agent', name: 'Name', namePh: 'e.g. Support Assistant', desc: 'Description',
      descPh: 'One sentence about what this Agent does', model: 'Model', selectModel: 'Select model',
      prompt: 'Prompt', promptPh: 'System prompt defining the Agent role and rules',
      temperature: 'Temperature', cancel: 'Cancel', createPublish: 'Create & publish'
    },
    knowledgeList: {
      searchPh: 'Search name/description', visibilityPh: 'Visibility', private: 'Only me',
      tenant: 'All team members', partial: 'Selected members', search: 'Search', create: 'New Knowledge Base',
      empty: 'No knowledge bases. Create one from the top right.', embedding: 'Embedding model', noDesc: 'No description',
      docs: 'Documents', chunks: 'Chunks', creator: 'Creator', edit: 'Edit', delete: 'Delete',
      editTitle: 'Edit Knowledge Base', name: 'Name', desc: 'Description', model: 'Embedding model',
      modelLocked: 'Documents already use this model and it cannot be switched', cancel: 'Cancel', save: 'Save',
      createTitle: 'New Knowledge Base', namePh: 'e.g. Product Manuals',
      modelHint: 'Pull the model in Ollama first; embeddings of different dimensions use separate tables',
      visibility: 'Visibility', createBtn: 'Create'
    },
    page: {
      agents: 'Agents', agentEdit: 'Edit Agent', chat: 'Chat', square: 'Agent Square',
      templates: 'Templates', approvals: 'Approvals', audit: 'Audit Log', billing: 'Billing',
      cases: 'Case Library', eval: 'Agent Evaluation', knowledge: 'Knowledge Base', mcp: 'MCP Server',
      memories: 'Long-term Memory', metrics: 'Metrics', observability: 'Observability', platform: 'Platform · Tenants',
      prompts: 'Prompt Templates', provider: 'Model Providers', skills: 'Skills', settings: 'System Settings (Platform)',
      tenant: 'Tenant Settings', tools: 'Tools', usage: 'Usage', users: 'Members'
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
