<template>
  <div class="page">
    <div class="toolbar">
      <span class="title">MCP Server</span>
      <span class="sub">添加 MCP 服务 → 点击「验证」检测连通性 → 同步工具；同步后的工具可在 Agent 中绑定</span>
      <div class="spacer" />
      <el-button @click="load">刷新</el-button>
      <el-button type="primary" :disabled="!canManage" @click="openCreate">添加 MCP Server</el-button>
    </div>

    <el-alert v-if="!canManage" type="info" :closable="false" show-icon class="mb12"
              title="只读模式：添加/验证 MCP Server 需要 Owner 或管理员权限（tool:manage）" />

    <el-empty v-if="!loading && list.length === 0" description="尚未添加 MCP Server">
      <el-button type="primary" :disabled="!canManage" @click="openCreate">添加第一个 MCP Server</el-button>
      <span v-if="!canManage" class="hint">请联系租户管理员添加</span>
    </el-empty>
    <el-table v-else :data="list" size="small" v-loading="loading">
      <el-table-column prop="name" label="名称" width="180" />
      <el-table-column prop="transport" label="传输" width="90" />
      <el-table-column prop="endpoint" label="地址" min-width="240" show-overflow-tooltip />
      <el-table-column prop="authType" label="鉴权" width="100" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="healthType(row.healthStatus)">
            {{ healthText(row.healthStatus) }}
          </el-tag>
          <el-tag v-if="row.toolsChangedAt" size="small" type="warning" class="ml4">工具待同步</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="toolCount" label="工具数" width="80" />
      <el-table-column label="最后同步" width="160">
        <template #default="{ row }">{{ fmt(row.lastSyncAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="300">
        <template #default="{ row }">
          <el-button size="small" text :loading="testingId === row.id" :disabled="!canManage"
                     @click="test(row)">验证</el-button>
          <el-button v-if="canManage" size="small" text type="primary" @click="openSync(row)">同步工具</el-button>
          <el-button v-if="canManage" size="small" text @click="openCapabilities(row)">能力</el-button>
          <el-button size="small" text @click="openTools(row)">已同步</el-button>
          <el-button v-if="canManage" size="small" text @click="openEdit(row)">编辑</el-button>
          <el-button v-if="canManage" size="small" text type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 接入/编辑 -->
    <el-dialog v-model="editVisible" :title="editForm.id ? '编辑 MCP Server' : '添加 MCP Server'" width="620px">
      <div class="preset-row">
        <span class="hint">快速预设：</span>
        <el-button size="small" @click="applyPreset('feishu')">飞书 MCP</el-button>
        <el-button size="small" @click="applyPreset('zhipu')">智谱 MCP</el-button>
        <el-button size="small" @click="applyPreset('filesystem')">本地文件系统（stdio）</el-button>
        <span class="hint">Endpoint/Token 请按官方文档填写，可修改</span>
      </div>
      <el-form label-width="90px">
        <el-form-item label="名称" required>
          <el-input v-model="editForm.name" maxlength="128" placeholder="如：内部工具服务" />
        </el-form-item>
        <el-form-item label="传输方式">
          <el-select v-model="editForm.transport" style="width: 200px">
            <el-option label="Streamable HTTP" value="HTTP" />
            <el-option label="SSE（旧版）" value="SSE" />
            <el-option label="STDIO（本地进程）" value="STDIO" />
          </el-select>
        </el-form-item>
        <template v-if="editForm.transport === 'STDIO'">
          <el-form-item label="启动命令" required>
            <el-input v-model="editForm.command" placeholder="npx / python3 / uvx" />
          </el-form-item>
          <el-form-item label="启动参数">
            <el-input v-model="editForm.argsText" type="textarea" :rows="2"
                      placeholder='JSON 数组，如 ["-y","@modelcontextprotocol/server-filesystem","/tmp"]' />
          </el-form-item>
          <el-form-item label="环境变量">
            <el-input v-model="editForm.envText" type="textarea" :rows="2"
                      placeholder='JSON 对象，如 {"GITHUB_TOKEN":"xxx"}（AES 加密存储；编辑时留空不修改）' />
          </el-form-item>
          <el-alert type="warning" :closable="false" show-icon class="mb12"
                    title="stdio 会在平台后端主机启动本地进程（支持会话复用与空闲回收）：仅限可信来源配置，命令不允许 shell 特殊字符或解释器。" />
        </template>
        <template v-else>
          <el-form-item label="地址" required>
            <el-input v-model="editForm.endpoint" placeholder="http://localhost:18081/mcp 或 https://mcp.feishu.cn/mcp" />
          </el-form-item>
          <el-form-item label="自定义请求头">
            <el-input v-model="editForm.headersText" type="textarea" :rows="2"
                      placeholder='JSON 对象，如 {"X-Tenant-Id":"xxx"}（Authorization 请用鉴权方式）' />
          </el-form-item>
          <el-form-item label="鉴权方式">
            <el-select v-model="editForm.authType" style="width: 160px">
              <el-option label="无" value="NONE" />
              <el-option label="API Key" value="API_KEY" />
              <el-option label="Bearer" value="BEARER" />
              <el-option label="OAuth" value="OAUTH" />
            </el-select>
          </el-form-item>
          <template v-if="editForm.authType === 'API_KEY'">
            <el-form-item label="Header">
              <el-input v-model="editForm.authHeader" placeholder="X-API-Key" style="width: 220px" />
            </el-form-item>
            <el-form-item label="Key">
              <el-input v-model="editForm.authValue" type="password" show-password
                        placeholder="密钥（****** 表示不修改）" />
            </el-form-item>
          </template>
          <el-form-item v-if="editForm.authType === 'BEARER'" label="Token">
            <el-input v-model="editForm.authToken" type="password" show-password
                      placeholder="Token（****** 表示不修改）" />
          </el-form-item>
          <el-form-item v-if="editForm.authType === 'OAUTH'" label="OAuth 2.1">
            <div class="oauth-box">
              <template v-if="oauthStatus?.connected">
                <el-tag size="small" type="success">已授权</el-tag>
                <span class="hint" v-if="oauthStatus?.expiresAt">
                  到期：{{ new Date(oauthStatus.expiresAt).toLocaleString() }}
                </span>
                <el-tag v-if="oauthStatus?.refreshable" size="small" type="info">可自动刷新</el-tag>
              </template>
              <el-tag v-else size="small" type="info">未授权</el-tag>
              <el-button size="small" type="primary" :loading="oauthLoading" @click="startOAuth">
                {{ oauthStatus?.connected ? '重新授权' : 'OAuth 授权' }}
              </el-button>
              <el-button size="small" v-if="oauthStatus?.connected" @click="disconnectOAuth">断开</el-button>
            </div>
            <div class="hint">PKCE + 动态客户端注册 + 自动刷新；点击授权会打开浏览器完成登录（先保存当前配置）。</div>
          </el-form-item>
        </template>
        <el-form-item label="提示">
          <span class="hint">保存后点击列表中的「验证」检测连通性；修改配置后需重新「同步工具」</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 同步工具 -->
    <el-dialog v-model="syncVisible" :title="`同步工具：${syncServerName}`" width="640px">
      <div class="hint mb8">勾选要同步到 Tool 管理的工具（已同步的会更新描述与 Schema）</div>
      <el-checkbox v-model="checkAll" @change="toggleAll">全选</el-checkbox>
      <el-table :data="discovered" size="small" max-height="360" v-loading="discovering">
        <el-table-column width="50">
          <template #default="{ row }">
            <el-checkbox v-model="row.checked" />
          </template>
        </el-table-column>
        <el-table-column prop="name" label="工具名" width="200" />
        <el-table-column prop="description" label="描述" min-width="260" show-overflow-tooltip />
      </el-table>
      <template #footer>
        <el-button @click="syncVisible = false">取消</el-button>
        <el-button type="primary" :loading="syncing" @click="doSync">同步选中</el-button>
      </template>
    </el-dialog>

    <!-- 已同步工具 -->
    <el-drawer v-model="toolsVisible" :title="`已同步工具：${toolsServerName}`" size="560px">
      <el-table :data="tools" size="small">
        <el-table-column prop="name" label="名称" width="160" />
        <el-table-column prop="toolKey" label="Tool Key" min-width="200" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'ENABLED' ? 'success' : 'info'">
              {{ row.status === 'ENABLED' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!tools.length" description="暂无已同步工具，请先同步" :image-size="60" />
    </el-drawer>

    <!-- MCP 资源 / 提示词发现（不落库，只读） -->
    <el-drawer v-model="capsVisible" :title="`MCP 能力：${capsServerName}`" size="680px">
      <div v-loading="capsLoading">
        <el-alert v-if="capsWarnings.length" type="warning" :closable="false" show-icon class="mb12"
                  :title="capsWarnings.join('；')" />
        <el-tabs v-model="capsTab">
          <el-tab-pane :label="`工具 (${caps.tools.length})`" name="tools">
            <el-table :data="caps.tools" size="small" max-height="420">
              <el-table-column prop="name" label="名称" width="180" />
              <el-table-column prop="description" label="描述" show-overflow-tooltip />
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="`资源 (${caps.resources.length})`" name="resources">
            <el-table :data="caps.resources" size="small" max-height="340">
              <el-table-column prop="name" label="名称" width="150" />
              <el-table-column prop="uri" label="URI" min-width="220" show-overflow-tooltip />
              <el-table-column prop="mimeType" label="类型" width="120" />
              <el-table-column label="操作" width="80">
                <template #default="{ row }">
                  <el-button size="small" text type="primary" @click="readResource(row)">读取</el-button>
                </template>
              </el-table-column>
            </el-table>
            <div v-if="caps.resourceTemplates.length" class="mt8">
              <div class="hint">资源模板</div>
              <el-table :data="caps.resourceTemplates" size="small" max-height="140">
                <el-table-column prop="name" label="名称" width="150" />
                <el-table-column prop="uriTemplate" label="URI 模板" show-overflow-tooltip />
              </el-table>
            </div>
          </el-tab-pane>
          <el-tab-pane :label="`提示词 (${caps.prompts.length})`" name="prompts">
            <el-table :data="caps.prompts" size="small" max-height="420">
              <el-table-column prop="name" label="名称" width="180" />
              <el-table-column prop="description" label="描述" show-overflow-tooltip />
            </el-table>
            <div class="hint mt8">提示词参数/渲染由调用方通过后端 prompts/get 接口使用。</div>
          </el-tab-pane>
        </el-tabs>
      </div>
    </el-drawer>

    <el-dialog v-model="resourceVisible" :title="`资源内容：${resourceName}`" width="680px">
      <pre class="resource-pre">{{ resourceText }}</pre>
      <template #footer><el-button @click="resourceVisible = false">关闭</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'
import { useUserStore } from '../stores/user'

const userStore = useUserStore()
const route = useRoute()
const router = useRouter()
const canManage = computed(() => {
  const perms = userStore.userInfo?.permissions || []
  return userStore.userInfo?.isPlatformAdmin || perms.includes('tool:manage')
})

const list = ref<any[]>([])
const loading = ref(false)
const testingId = ref(0)

const editVisible = ref(false)
const saving = ref(false)
const oauthStatus = ref<any>(null)
const oauthLoading = ref(false)
const editForm = reactive({
  id: 0, name: '', endpoint: '', transport: 'HTTP', headersText: '{}', authType: 'NONE',
  authHeader: 'X-API-Key', authValue: '', authToken: '',
  command: '', argsText: '[]', envText: ''
})

function applyPreset(type: string) {
  if (type === 'feishu') {
    Object.assign(editForm, {
      name: '飞书 MCP', endpoint: 'https://mcp.feishu.cn/mcp', transport: 'HTTP',
      authType: 'BEARER', headersText: '{}'
    })
  } else if (type === 'zhipu') {
    Object.assign(editForm, {
      name: '智谱 MCP', endpoint: 'https://open.bigmodel.cn/api/mcp/sse', transport: 'SSE',
      authType: 'BEARER', headersText: '{}'
    })
  } else if (type === 'filesystem') {
    Object.assign(editForm, {
      name: '本地文件系统（示例）', transport: 'STDIO', command: 'npx',
      argsText: '["-y","@modelcontextprotocol/server-filesystem","/tmp"]', envText: '{}'
    })
  }
}

const syncVisible = ref(false)
const discovering = ref(false)
const syncing = ref(false)
const syncServerId = ref(0)
const syncServerName = ref('')
const discovered = ref<any[]>([])
const checkAll = ref(true)

const toolsVisible = ref(false)
const tools = ref<any[]>([])
const toolsServerName = ref('')

const capsVisible = ref(false)
const capsLoading = ref(false)
const capsServerId = ref(0)
const capsServerName = ref('')
const capsTab = ref('tools')
const capsWarnings = ref<string[]>([])
const caps = ref<any>({ tools: [], resources: [], resourceTemplates: [], prompts: [] })
const resourceVisible = ref(false)
const resourceText = ref('')
const resourceName = ref('')

async function openCapabilities(row: any) {
  capsServerId.value = row.id
  capsServerName.value = row.name
  capsVisible.value = true
  capsLoading.value = true
  capsTab.value = 'tools'
  try {
    const res = await http.get(`/mcp-servers/${row.id}/discover`) as any
    caps.value = {
      tools: res.tools || [], resources: res.resources || [],
      resourceTemplates: res.resourceTemplates || [], prompts: res.prompts || []
    }
    capsWarnings.value = [...(res.warnings || [])]
    if (res.toolsChangedAt) capsWarnings.value.unshift('服务端报告工具列表已变更，请重新同步工具')
  } catch (e: any) {
    caps.value = { tools: [], resources: [], resourceTemplates: [], prompts: [] }
    capsWarnings.value = [e?.response?.data?.message || '能力发现失败']
  } finally {
    capsLoading.value = false
  }
}

async function readResource(row: any) {
  try {
    const res = await http.get(`/mcp-servers/${capsServerId.value}/resources/read`,
      { params: { uri: row.uri } }) as any
    resourceName.value = row.name || row.uri
    resourceText.value = (res.contents || []).map((c: any) => c.text || c.blob || '').join('\n')
    resourceVisible.value = true
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '读取资源失败')
  }
}

onMounted(async () => {
  await load()
  if (route.query.oauth) {
    const status = String(route.query.oauth)
    const sid = Number(route.query.serverId)
    if (status === 'success') {
      ElMessage.success('OAuth 授权成功')
      const row = list.value.find((r: any) => r.id === sid)
      if (row) await openEdit(row)
    } else {
      ElMessage.error('OAuth 授权失败：' + (route.query.message || '未知错误'))
    }
    const query = { ...route.query }
    delete query.oauth
    delete query.serverId
    delete query.message
    await router.replace({ query })
  }
  if (route.query.create === '1') {
    if (canManage.value) openCreate()
    else ElMessage.warning('当前账号无添加 MCP Server 权限')
    const query = { ...route.query }
    delete query.create
    router.replace({ query })
  }
})

async function load() {
  loading.value = true
  try {
    const res = await http.get('/mcp-servers') as any
    list.value = res?.list || []
  } finally {
    loading.value = false
  }
}

function healthText(s?: string) {
  if (s === 'ONLINE') return '在线'
  if (s === 'OFFLINE') return '离线'
  if (s === 'ERROR') return '异常'
  return '未检测'
}

function healthType(s?: string) {
  if (s === 'ONLINE') return 'success'
  if (s === 'ERROR') return 'danger'
  if (s === 'OFFLINE') return 'warning'
  return 'info'
}

function openCreate() {
  Object.assign(editForm, {
    id: 0, name: '', endpoint: '', transport: 'HTTP', headersText: '{}', authType: 'NONE',
    authHeader: 'X-API-Key', authValue: '', authToken: '',
    command: '', argsText: '[]', envText: ''
  })
  oauthStatus.value = null
  editVisible.value = true
}

async function openEdit(row: any) {
  const detail = await http.get(`/mcp-servers/${row.id}`) as any
  const auth = detail.auth || {}
  oauthStatus.value = detail.oauth || null
  Object.assign(editForm, {
    id: detail.id,
    name: detail.name || '',
    endpoint: detail.endpoint || '',
    transport: detail.transport || 'HTTP',
    headersText: JSON.stringify(detail.customHeaders || {}, null, 2),
    authType: detail.authType || 'NONE',
    authHeader: auth.header || 'X-API-Key',
    authValue: auth.value || '',
    authToken: auth.token || '',
    command: detail.command || '',
    argsText: JSON.stringify(detail.args || []),
    envText: '{}'
  })
  editVisible.value = true
}

async function save(silent = false): Promise<boolean> {
  if (!editForm.name.trim()) { ElMessage.warning('名称必填'); return false }
  if (editForm.transport === 'STDIO') {
    if (!editForm.command.trim()) { ElMessage.warning('STDIO 需要填写启动命令'); return false }
  } else if (!editForm.endpoint.trim()) {
    ElMessage.warning('地址必填'); return false
  }
  saving.value = true
  try {
    const payload: any = { name: editForm.name.trim(), transport: editForm.transport }
    if (editForm.transport === 'STDIO') {
      let args: any
      try { args = JSON.parse(editForm.argsText || '[]') } catch { ElMessage.error('启动参数不是合法 JSON 数组'); return false }
      if (!Array.isArray(args)) { ElMessage.error('启动参数需为 JSON 数组'); return false }
      let env: any
      try { env = JSON.parse(editForm.envText || '{}') } catch { ElMessage.error('环境变量不是合法 JSON 对象'); return false }
      payload.command = editForm.command.trim()
      payload.args = args
      if (Object.keys(env).length) payload.env = env
    } else {
      const auth: any = { type: editForm.authType }
      if (editForm.authType === 'API_KEY') {
        auth.header = editForm.authHeader || 'X-API-Key'
        auth.value = editForm.authValue
      } else if (editForm.authType === 'BEARER') {
        auth.token = editForm.authToken
      }
      let headers: any = {}
      try { headers = JSON.parse(editForm.headersText || '{}') } catch { ElMessage.error('自定义请求头不是合法 JSON'); return false }
      payload.endpoint = editForm.endpoint.trim()
      payload.customHeaders = headers
      payload.authType = editForm.authType
      payload.auth = auth
    }
    if (editForm.id) {
      await http.put(`/mcp-servers/${editForm.id}`, payload)
    } else {
      const res = await http.post('/mcp-servers', payload) as any
      if (res?.id) editForm.id = res.id
    }
    if (!silent) {
      ElMessage.success('已保存')
      editVisible.value = false
    }
    await load()
    return true
  } finally {
    saving.value = false
  }
}

async function startOAuth() {
  // 先同步打开窗口（保留用户手势，避免异步后被浏览器拦截）
  const popup = window.open('about:blank', 'mcp-oauth', 'width=620,height=760')
  oauthLoading.value = true
  try {
    const saved = await save(true)
    if (!saved) { popup?.close(); return }
    const res = await http.post(`/mcp-servers/${editForm.id}/oauth/authorize`) as any
    if (popup && !popup.closed) popup.location.href = res.authorizeUrl
    else window.location.href = res.authorizeUrl
    ElMessage.info('已打开授权窗口，完成后回到本页查看状态')
  } catch (e: any) {
    popup?.close()
    ElMessage.error(e?.response?.data?.message || e?.message || 'OAuth 授权发起失败')
  } finally {
    oauthLoading.value = false
  }
}

async function disconnectOAuth() {
  try {
    await ElMessageBox.confirm('断开后需要重新授权才能调用该 MCP Server，确认继续？', '断开 OAuth')
  } catch { return }
  await http.post(`/mcp-servers/${editForm.id}/oauth/disconnect`)
  oauthStatus.value = { connected: false }
  ElMessage.success('已断开 OAuth 授权')
}

async function test(row: any) {
  testingId.value = row.id
  try {
    const res = await http.post(`/mcp-servers/${row.id}/test`) as any
    ElMessage[res.success ? 'success' : 'error'](
      res.success ? `连接正常（${res.latencyMs}ms）` : `验证失败：${res.message}`)
    await load()
  } finally {
    testingId.value = 0
  }
}

async function openSync(row: any) {
  syncServerId.value = row.id
  syncServerName.value = row.name
  syncVisible.value = true
  discovering.value = true
  try {
    const res = await http.get(`/mcp-servers/${row.id}/discover`) as any
    discovered.value = (res.tools || []).map((t: any) => ({ ...t, checked: true }))
    if (res.toolsChangedAt) ElMessage.warning('服务端报告工具列表已变更，请确认后重新同步')
    checkAll.value = true
  } finally {
    discovering.value = false
  }
}

function toggleAll(v: boolean) {
  discovered.value.forEach(t => { t.checked = v })
}

async function doSync() {
  const names = discovered.value.filter(t => t.checked).map(t => t.name)
  if (!names.length) { ElMessage.warning('请选择要同步的工具'); return }
  syncing.value = true
  try {
    const res = await http.post(`/mcp-servers/${syncServerId.value}/sync`, { toolNames: names }) as any
    ElMessage.success(`已同步 ${res.total} 个工具`)
    syncVisible.value = false
    await load()
  } finally {
    syncing.value = false
  }
}

async function openTools(row: any) {
  toolsServerName.value = row.name
  const res = await http.get(`/mcp-servers/${row.id}/tools`) as any
  tools.value = res.list || []
  toolsVisible.value = true
}

async function remove(row: any) {
  const refs = await http.get(`/mcp-servers/${row.id}/references`) as any
  const agents: string[] = refs.agents || []
  const skills: string[] = refs.skills || []
  const refText = [
    agents.length ? `Agent：${agents.join('、')}` : '',
    skills.length ? `技能：${skills.join('、')}` : ''
  ].filter(Boolean).join('；')
  await ElMessageBox.confirm(
    `删除 Server「${row.name}」将同时删除其已同步工具。${refText ? '当前被引用（' + refText + '），请先解绑。' : ''}确认删除？`,
    '删除 MCP Server', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  await http.delete(`/mcp-servers/${row.id}`)
  ElMessage.success('已删除')
  await load()
}

function fmt(v?: string) {
  return v ? String(v).replace('T', ' ').slice(0, 16) : '—'
}
</script>

<style scoped>
.page { background: #fff; border: 1px solid #e4e7ed; border-radius: 6px; padding: 14px; }
.toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
.title { font-weight: 600; font-size: 15px; white-space: nowrap; flex-shrink: 0; }
.sub { color: #909399; font-size: 12px; margin-left: 8px; }
.spacer { flex: 1; }
.hint { color: #909399; font-size: 12px; }
.mb12 { margin-bottom: 12px; }
.preset-row { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.mb8 { margin-bottom: 8px; }
.oauth-box { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.ml4 { margin-left: 4px; }
.resource-pre { white-space: pre-wrap; word-break: break-all; max-height: 420px; overflow: auto; background: var(--el-fill-color-light); padding: 12px; border-radius: 4px; }
</style>
