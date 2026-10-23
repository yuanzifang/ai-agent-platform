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
    approval: {
      sub: '工作流运行到审批节点会在此暂停；审批通过后自动继续执行', pending: '待审批',
      approved: '已通过', rejected: '已拒绝', expired: '已超时', all: '全部', refresh: '刷新',
      empty: '暂无审批任务', id: 'ID', agent: 'Agent', title: '审批事项', role: '审批角色',
      anyRole: '不限', content: '待审内容', status: '状态', submitted: '提交时间', expireAt: '超时时间',
      handler: '处理人/意见', actions: '操作', approve: '通过', reject: '拒绝', viewRun: '查看运行',
      approveTitle: '通过审批', rejectTitle: '拒绝审批', item: '审批事项', comment: '审批意见',
      commentApprovePh: '可选，如：同意退款', commentRejectPh: '必填，如：金额超限，转人工',
      cancel: '取消', confirmApprove: '确认通过', confirmReject: '确认拒绝',
      runDetail: '运行详情', runId: '运行 ID', currentNode: '当前节点', input: '输入', output: '输出',
      track: '节点轨迹', rejectReason: '拒绝时请填写原因', approvedToast: '已通过，运行状态：', rejectedToast: '已拒绝'
    },
    tpl: {
      sub: '预置 Agent 模板，一键创建后自动填充提示词、能力与工具/Skill 绑定', searchPh: '搜索模板',
      category: '分类', search: '搜索', empty: '暂无模板', builtin: '内置', tools: '工具', skills: '技能',
      noBindings: '无绑定工具', preview: '预览', use: '使用模板创建', previewTitle: '模板预览：',
      desc: '描述', capabilities: '能力', stream: '流式输出', function: '函数调用', variables: '变量', bindings: '绑定', prompt: '系统提示词',
      opening: '欢迎语', streamOn: '开', streamOff: '关', close: '关闭', created: '已从模板创建 Agent，请按需调整知识库等配置'
    },
    square: {
      sub: '本租户已发布的 Agent，按热度/评分排序', searchPh: '搜索名称/描述', category: '分类', tag: '标签',
      sortHot: '按热度', sortRating: '按评分', sortNew: '最新发布', favoritesOnly: '我的收藏', search: '搜索',
      reports: '举报处理', empty: '暂无已上架的 Agent', noDesc: '暂无描述', calls: '调用', successRate: '成功率',
      rating: '评分', favorites: '收藏', detail: '详情', unfavorite: '取消收藏', chat: '对话',
      reportTitle: '举报处理', reportEmpty: '暂无举报', reporter: '举报人', reason: '理由', time: '时间',
      status: '状态', actions: '操作', handle: '处理', dismiss: '驳回', handled: '已处理', unclassified: '未分类',
      version: '版本', delisted: '已下架', opening: '欢迎语', variables: '输入参数', useChat: '使用（对话）',
      copyAgent: '复制为我的 Agent', report: '举报', reviews: '用户评价', myRating: '我的评价',
      ratingPh: '说说使用体验（可选）', submitRating: '提交评价', onlyUsed: '仅使用过该 Agent 的用户可评价',
      rateAfterUse: '使用该 Agent 后可进行评价', anonUser: '用户', noComment: '（无评论）',
      publisherReply: '发布者回复：', replyPh: '回复该评价', reply: '回复', noReviews: '暂无评价',
      reportReasonTitle: '举报 Agent', reportReasonPh: '如：内容违规、信息不实等', submit: '提交', cancel: '取消',
      reportSuccess: '举报已提交，平台管理员将处理', noPermission: '无使用权限',
      copyConfirmPrefix: '将复制「', copyConfirmSuffix: '」的配置与绑定，创建归属于你的副本。继续？',
      copyTitle: 'Fork Agent', copied: '已创建副本', ratingSubmitted: '评价已提交',
      replyRequired: '请输入回复内容', replied: '已回复', resolved: '已处理', dismissed: '已驳回'
    },
    prompt: {
      sub: '模板内容支持双花括号变量，在 Agent 提示词中可一键插入', searchPh: '搜索名称/内容', category: '分类',
      search: '搜索', create: '新建模板', name: '名称', variables: '变量', contentPreview: '内容预览',
      updated: '更新时间', actions: '操作', edit: '编辑', delete: '删除', editTitle: '编辑模板', newTitle: '新建模板',
      namePh: '如：客服话术生成', categoryPh: '如：客服 / 营销 / 分析', content: '内容',
      contentPh: '模板内容，使用 {{key}} 定义变量', optimize: 'AI 优化',
      optimizeHint: '保留原有变量占位，优化结果需手动应用', noVars: '未检测到变量', cancel: '取消', save: '保存',
      optTitle: 'AI 优化模板内容', optHint: '保留双花括号变量与原始业务意图；优化结果需手动应用，不会自动保存。',
      optDirection: '优化方向', optional: '可选', structured: '结构化', concise: '更简洁', professional: '更专业',
      serviceTone: '客服语气', extraReq: '额外要求', extraPh: '如：保留变量；补充拒答边界；不要改变语言',
      currentContent: '当前内容', chars: '字', empty: '（空）', start: '开始优化', result: '优化结果',
      replace: '替换原内容', append: '追加到末尾', clear: '清除结果', contentRequired: '请先填写模板内容',
      optDone: '优化完成，请确认后应用', replaced: '已替换模板内容（尚未保存）',
      appended: '已追加到模板内容末尾（尚未保存）', nameRequired: '名称必填', saved: '已保存',
      deleteConfirm: '确认删除该模板？', deleteTitle: '删除模板', deleted: '已删除',
      deleteRefsPrefix: '以下 Agent 的提示词包含该模板内容：', deleteRefsSuffix: '。删除后请检查其提示词。'
    },
    tool: {
      sub: '内置工具只读；API 工具支持请求配置/鉴权/超时重试；MCP 工具由 MCP Server 同步',
      searchPh: '搜索名称/描述', type: '类型', builtin: '内置', search: '搜索', createApi: '新建 API 工具',
      addMcp: '添加 MCP Server', readonly: '只读模式：新建/测试工具需要 Owner 或管理员权限（tool:manage）',
      empty: '暂无工具', name: '名称', category: '分类', desc: '描述', timeoutRetry: '超时/重试',
      times: '次', status: '状态', enabled: '启用', disabled: '停用', actions: '操作', test: '测试',
      edit: '编辑', publish: '发布', versions: '版本', delete: '删除', editTitle: '编辑工具',
      newApi: '新建 API 工具', mcpHint: '这里创建 API 工具；MCP 工具请到「MCP Server」添加服务后同步生成。',
      nameLabel: '名称', descPh: '描述工具能力，LLM 依据该描述决定何时调用', tags: '标签',
      tagsPh: '逗号分隔', apiUrl: 'API 地址', method: '请求方法', headers: '请求头',
      headersPh: 'JSON 对象，如 {"Content-Type":"application/json"}', paramSchema: '参数 Schema',
      paramName: '参数名', typeLabel: '类型', location: '位置', required: '必填', note: '说明', remove: '删',
      addParam: '＋ 添加参数', bodyTemplate: '请求体模板',
      bodyPh: 'POST/PUT 时使用，支持 {{参数名}} 占位，如 {"q":"{{query}}"}',
      responsePath: '响应解析路径', responsePh: '如 data.items（点路径，可含数组下标）', authType: '鉴权方式',
      none: '无', keyConfig: 'Key 配置', headerNamePh: 'Header 名，默认 X-API-Key',
      keyPh: 'Key（****** 表示不修改）', bearerPh: 'Bearer Token（****** 表示不修改）',
      username: '用户名', password: '密码', passwordPh: '密码（****** 表示不修改）', timeoutMs: '超时(ms)',
      retryTimes: '重试次数', cancel: '取消', save: '保存', testTitle: '测试工具：',
      testPh: '调用参数 JSON，如 {"expression":"(12+3)*4"}', runTest: '执行测试',
      testHint: '测试不写入正式 Trace', output: '输出', versionHistory: '版本历史', version: '版本',
      time: '时间', rollback: '回滚', noVersions: '暂无版本，请先发布', noPermission: '当前账号无新建工具权限',
      invalidJson: '请求头不是合法 JSON', nameRequired: '名称必填', urlRequired: 'API 地址必填',
      saved: '已保存', published: '已发布', rollbackConfirm: '回滚到 ', rollbackSuffix: ' 将生成新版本，继续？',
      rollbackTitle: '回滚确认', rolledBack: '已回滚', deleteTool: '删除工具',
      refsAgent: 'Agent：', refsSkill: '技能：', refsSoft: '该工具被引用（', refsSoftSuffix: '），将软删除并显示「已失效」。',
      refsNone: '该工具未被引用，将永久删除。', deleteConfirm: '确认删除工具「', deleteSuffix: '」？',
      deleted: '已删除', softDeleted: '已软删除（已失效）'
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
    approval: {
      sub: 'Workflow runs pause at approval nodes and resume after approval', pending: 'Pending',
      approved: 'Approved', rejected: 'Rejected', expired: 'Expired', all: 'All', refresh: 'Refresh',
      empty: 'No approval tasks', id: 'ID', agent: 'Agent', title: 'Approval', role: 'Required role',
      anyRole: 'Any', content: 'Content', status: 'Status', submitted: 'Submitted', expireAt: 'Expires',
      handler: 'Handler / comment', actions: 'Actions', approve: 'Approve', reject: 'Reject', viewRun: 'View run',
      approveTitle: 'Approve', rejectTitle: 'Reject', item: 'Approval', comment: 'Comment',
      commentApprovePh: 'Optional, e.g. approved', commentRejectPh: 'Required, e.g. amount exceeds limit',
      cancel: 'Cancel', confirmApprove: 'Confirm approve', confirmReject: 'Confirm reject',
      runDetail: 'Run details', runId: 'Run ID', currentNode: 'Current node', input: 'Input', output: 'Output',
      track: 'Node timeline', rejectReason: 'Please provide a rejection reason', approvedToast: 'Approved, run status: ', rejectedToast: 'Rejected'
    },
    tpl: {
      sub: 'Preset Agent templates with prompts, capabilities and tool/skill bindings', searchPh: 'Search templates',
      category: 'Category', search: 'Search', empty: 'No templates', builtin: 'Built-in', tools: 'Tools', skills: 'Skills',
      noBindings: 'No bound tools', preview: 'Preview', use: 'Use template', previewTitle: 'Template preview: ',
      desc: 'Description', capabilities: 'Capabilities', stream: 'Streaming', function: 'Function calling', variables: 'Variables', bindings: 'Bindings', prompt: 'System prompt',
      opening: 'Greeting', streamOn: 'On', streamOff: 'Off', close: 'Close', created: 'Agent created from template; adjust knowledge bases as needed'
    },
    square: {
      sub: 'Published Agents in this tenant, sorted by popularity/rating', searchPh: 'Search name/description', category: 'Category', tag: 'Tag',
      sortHot: 'Popularity', sortRating: 'Rating', sortNew: 'Newest', favoritesOnly: 'My favorites', search: 'Search',
      reports: 'Reports', empty: 'No listed Agents', noDesc: 'No description', calls: 'Calls', successRate: 'Success',
      rating: 'Rating', favorites: 'Favorites', detail: 'Details', unfavorite: 'Unfavorite', chat: 'Chat',
      reportTitle: 'Reports', reportEmpty: 'No reports', reporter: 'Reporter', reason: 'Reason', time: 'Time',
      status: 'Status', actions: 'Actions', handle: 'Resolve', dismiss: 'Dismiss', handled: 'Handled', unclassified: 'Uncategorized',
      version: 'Version', delisted: 'Delisted', opening: 'Greeting', variables: 'Inputs', useChat: 'Use (chat)',
      copyAgent: 'Copy to my Agents', report: 'Report', reviews: 'User reviews', myRating: 'My rating',
      ratingPh: 'Share your experience (optional)', submitRating: 'Submit rating', onlyUsed: 'Only users who have used this Agent can rate',
      rateAfterUse: 'Use this Agent to leave a rating', anonUser: 'User', noComment: '(No comment)',
      publisherReply: 'Publisher reply: ', replyPh: 'Reply to this review', reply: 'Reply', noReviews: 'No reviews yet',
      reportReasonTitle: 'Report Agent', reportReasonPh: 'e.g. inappropriate content or misinformation', submit: 'Submit', cancel: 'Cancel',
      reportSuccess: 'Report submitted; platform admins will review it', noPermission: 'No permission to use',
      copyConfirmPrefix: 'Copy the configuration and bindings of "', copyConfirmSuffix: '" into your own Agent. Continue?',
      copyTitle: 'Fork Agent', copied: 'Copy created', ratingSubmitted: 'Rating submitted',
      replyRequired: 'Please enter a reply', replied: 'Reply submitted', resolved: 'Resolved', dismissed: 'Dismissed'
    },
    prompt: {
      sub: 'Template content supports double-brace variables and can be inserted into Agent prompts', searchPh: 'Search name/content', category: 'Category',
      search: 'Search', create: 'New template', name: 'Name', variables: 'Variables', contentPreview: 'Preview',
      updated: 'Updated', actions: 'Actions', edit: 'Edit', delete: 'Delete', editTitle: 'Edit template', newTitle: 'New template',
      namePh: 'e.g. Support script generator', categoryPh: 'e.g. Support / Marketing / Analysis', content: 'Content',
      contentPh: 'Template content; use {{key}} for variables', optimize: 'AI optimize',
      optimizeHint: 'Existing variable placeholders are kept; apply the result manually', noVars: 'No variables detected', cancel: 'Cancel', save: 'Save',
      optTitle: 'AI optimize template', optHint: 'Keep double-brace variables and original intent; the result is not saved automatically.',
      optDirection: 'Optimization goal', optional: 'Optional', structured: 'Structured', concise: 'More concise', professional: 'More professional',
      serviceTone: 'Support tone', extraReq: 'Extra requirements', extraPh: 'e.g. keep variables; add refusal boundaries; keep the language',
      currentContent: 'Current content', chars: 'chars', empty: '(empty)', start: 'Start optimizing', result: 'Optimized result',
      replace: 'Replace content', append: 'Append', clear: 'Clear result', contentRequired: 'Please enter template content first',
      optDone: 'Optimization complete; review and apply', replaced: 'Template content replaced (not saved yet)',
      appended: 'Appended to template content (not saved yet)', nameRequired: 'Name is required', saved: 'Saved',
      deleteConfirm: 'Delete this template?', deleteTitle: 'Delete template', deleted: 'Deleted',
      deleteRefsPrefix: 'These Agents reference this template content: ', deleteRefsSuffix: '. Check their prompts after deletion.'
    },
    tool: {
      sub: 'Built-in tools are read-only; API tools support request config/auth/retry; MCP tools sync from MCP Server',
      searchPh: 'Search name/description', type: 'Type', builtin: 'Built-in', search: 'Search', createApi: 'New API tool',
      addMcp: 'Add MCP Server', readonly: 'Read-only: creating/testing tools requires Owner or admin (tool:manage)',
      empty: 'No tools', name: 'Name', category: 'Category', desc: 'Description', timeoutRetry: 'Timeout/retry',
      times: 'times', status: 'Status', enabled: 'Enabled', disabled: 'Disabled', actions: 'Actions', test: 'Test',
      edit: 'Edit', publish: 'Publish', versions: 'Versions', delete: 'Delete', editTitle: 'Edit tool',
      newApi: 'New API tool', mcpHint: 'Create API tools here; add MCP tools via MCP Server and sync.',
      nameLabel: 'Name', descPh: 'Describe the tool; the LLM uses this to decide when to call it', tags: 'Tags',
      tagsPh: 'Comma separated', apiUrl: 'API URL', method: 'Method', headers: 'Headers',
      headersPh: 'JSON object, e.g. {"Content-Type":"application/json"}', paramSchema: 'Parameter schema',
      paramName: 'Name', typeLabel: 'Type', location: 'In', required: 'Required', note: 'Description', remove: 'Remove',
      addParam: '+ Add parameter', bodyTemplate: 'Body template',
      bodyPh: 'Used for POST/PUT; supports {{param}} placeholders, e.g. {"q":"{{query}}"}',
      responsePath: 'Response path', responsePh: 'e.g. data.items (dot path, array index allowed)', authType: 'Auth',
      none: 'None', keyConfig: 'Key config', headerNamePh: 'Header name, default X-API-Key',
      keyPh: 'Key (****** means unchanged)', bearerPh: 'Bearer Token (****** means unchanged)',
      username: 'Username', password: 'Password', passwordPh: 'Password (****** means unchanged)', timeoutMs: 'Timeout(ms)',
      retryTimes: 'Retries', cancel: 'Cancel', save: 'Save', testTitle: 'Test tool: ',
      testPh: 'Arguments JSON, e.g. {"expression":"(12+3)*4"}', runTest: 'Run test',
      testHint: 'Tests are not recorded in production traces', output: 'Output', versionHistory: 'Version history', version: 'Version',
      time: 'Time', rollback: 'Rollback', noVersions: 'No versions; publish first', noPermission: 'No permission to create tools',
      invalidJson: 'Headers must be valid JSON', nameRequired: 'Name is required', urlRequired: 'API URL is required',
      saved: 'Saved', published: 'Published', rollbackConfirm: 'Rollback to ', rollbackSuffix: ' will create a new version. Continue?',
      rollbackTitle: 'Rollback', rolledBack: 'Rolled back', deleteTool: 'Delete tool',
      refsAgent: 'Agents: ', refsSkill: 'Skills: ', refsSoft: 'This tool is referenced (', refsSoftSuffix: '); it will be soft-deleted and marked invalid.',
      refsNone: 'This tool is not referenced and will be permanently deleted.', deleteConfirm: 'Delete tool "', deleteSuffix: '"?',
      deleted: 'Deleted', softDeleted: 'Soft-deleted (invalid)'
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
