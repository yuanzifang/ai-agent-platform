<template>
  <div class="page">
    <div class="toolbar">
      <span class="title">{{ t('page.mcp') }}</span>
      <span class="sub">{{ t('mcpPage.sub') }}</span>
      <div class="spacer" />
      <el-button @click="load">{{ t('mcpPage.refresh') }}</el-button>
      <el-button type="primary" :disabled="!canManage" @click="openCreate">{{ t('mcpPage.add') }}</el-button>
    </div>

    <el-alert v-if="!canManage" type="info" :closable="false" show-icon class="mb12"
              :title="t('mcpPage.readonly')" />

    <el-empty v-if="!loading && list.length === 0" :description="t('mcpPage.empty')">
      <el-button type="primary" :disabled="!canManage" @click="openCreate">{{ t('mcpPage.addFirst') }}</el-button>
      <span v-if="!canManage" class="hint">{{ t('mcpPage.contactAdmin') }}</span>
    </el-empty>
    <el-table v-else :data="list" size="small" v-loading="loading">
      <el-table-column prop="name" :label="t('mcpPage.name')" width="180" />
      <el-table-column prop="transport" :label="t('mcpPage.transport')" width="90" />
      <el-table-column prop="endpoint" :label="t('mcpPage.endpoint')" min-width="240" show-overflow-tooltip />
      <el-table-column prop="authType" :label="t('mcpPage.auth')" width="100" />
      <el-table-column :label="t('mcpPage.status')" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="healthType(row.healthStatus)">
            {{ healthText(row.healthStatus) }}
          </el-tag>
          <el-tag v-if="row.toolsChangedAt" size="small" type="warning" class="ml4">{{ t('mcpPage.pendingSync') }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="toolCount" :label="t('mcpPage.toolCount')" width="80" />
      <el-table-column :label="t('mcpPage.lastSync')" width="160">
        <template #default="{ row }">{{ fmt(row.lastSyncAt) }}</template>
      </el-table-column>
      <el-table-column :label="t('mcpPage.actions')" width="300">
        <template #default="{ row }">
          <el-button size="small" text :loading="testingId === row.id" :disabled="!canManage"
                     @click="test(row)">{{ t('mcpPage.verify') }}</el-button>
          <el-button v-if="canManage" size="small" text type="primary" @click="openSync(row)">{{ t('mcpPage.sync') }}</el-button>
          <el-button v-if="canManage" size="small" text @click="openCapabilities(row)">{{ t('mcpPage.capabilities') }}</el-button>
          <el-button size="small" text @click="openTools(row)">{{ t('mcpPage.synced') }}</el-button>
          <el-button v-if="canManage" size="small" text @click="openEdit(row)">{{ t('mcpPage.edit') }}</el-button>
          <el-button v-if="canManage" size="small" text type="danger" @click="remove(row)">{{ t('mcpPage.delete') }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 接入/编辑 -->
    <el-dialog v-model="editVisible" :title="editForm.id ? t('mcpPage.editTitle') : t('mcpPage.newTitle')" width="620px">
      <div class="preset-row">
        <span class="hint">{{ t('mcpPage.preset') }}</span>
        <el-button size="small" @click="applyPreset('feishu')">{{ t('mcpPage.feishu') }}</el-button>
        <el-button size="small" @click="applyPreset('zhipu')">{{ t('mcpPage.zhipu') }}</el-button>
        <el-button size="small" @click="applyPreset('filesystem')">{{ t('mcpPage.filesystem') }}</el-button>
        <span class="hint">{{ t('mcpPage.presetHint') }}</span>
      </div>
      <el-form label-width="90px">
        <el-form-item :label="t('mcpPage.name')" required>
          <el-input v-model="editForm.name" maxlength="128" :placeholder="t('mcpPage.namePh')" />
        </el-form-item>
        <el-form-item :label="t('mcpPage.transportLabel')">
          <el-select v-model="editForm.transport" style="width: 200px">
            <el-option label="Streamable HTTP" value="HTTP" />
            <el-option :label="t('mcpPage.sse')" value="SSE" />
            <el-option :label="t('mcpPage.stdio')" value="STDIO" />
          </el-select>
        </el-form-item>
        <template v-if="editForm.transport === 'STDIO'">
          <el-form-item :label="t('mcpPage.command')" required>
            <el-input v-model="editForm.command" :placeholder="t('mcpPage.commandPh')" />
          </el-form-item>
          <el-form-item :label="t('mcpPage.args')">
            <el-input v-model="editForm.argsText" type="textarea" :rows="2"
                      :placeholder="t('mcpPage.argsPh')" />
          </el-form-item>
          <el-form-item :label="t('mcpPage.env')">
            <el-input v-model="editForm.envText" type="textarea" :rows="2"
                      :placeholder="t('mcpPage.envPh')" />
          </el-form-item>
          <el-alert type="warning" :closable="false" show-icon class="mb12"
                    :title="t('mcpPage.stdioWarning')" />
        </template>
        <template v-else>
          <el-form-item :label="t('mcpPage.endpoint')" required>
            <el-input v-model="editForm.endpoint" :placeholder="t('mcpPage.addrPh')" />
          </el-form-item>
          <el-form-item :label="t('mcpPage.headers')">
            <el-input v-model="editForm.headersText" type="textarea" :rows="2"
                      :placeholder="t('mcpPage.headersPh')" />
          </el-form-item>
          <el-form-item :label="t('mcpPage.authType')">
            <el-select v-model="editForm.authType" style="width: 160px">
              <el-option :label="t('mcpPage.none')" value="NONE" />
              <el-option label="API Key" value="API_KEY" />
              <el-option label="Bearer" value="BEARER" />
              <el-option label="OAuth" value="OAUTH" />
              <el-option label="飞书应用 (app_id/secret)" value="FEISHU_APP" />
            </el-select>
          </el-form-item>
          <template v-if="editForm.authType === 'API_KEY'">
            <el-form-item label="Header">
              <el-input v-model="editForm.authHeader" placeholder="X-API-Key" style="width: 220px" />
            </el-form-item>
            <el-form-item :label="t('mcpPage.key')">
              <el-input v-model="editForm.authValue" type="password" show-password
                        :placeholder="t('mcpPage.keyPh')" />
            </el-form-item>
          </template>
          <el-form-item v-if="editForm.authType === 'BEARER'" :label="t('mcpPage.token')">
            <el-input v-model="editForm.authToken" type="password" show-password
                      :placeholder="t('mcpPage.tokenPh')" />
          </el-form-item>
          <template v-if="editForm.authType === 'FEISHU_APP'">
            <el-form-item label="App ID">
              <el-input v-model="editForm.feishuAppId" placeholder="cli_xxxxxxxx" style="width: 260px" />
            </el-form-item>
            <el-form-item label="App Secret">
              <el-input v-model="editForm.feishuAppSecret" type="password" show-password
                        placeholder="飞书自建应用 Secret" style="width: 260px" />
            </el-form-item>
            <div class="hint" style="margin: -8px 0 12px 0">
              平台自动获取并缓存 tenant_access_token（约 2 小时），过期自动刷新。
            </div>
          </template>
          <el-form-item v-if="editForm.authType === 'OAUTH'" label="OAuth 2.1">
            <div class="oauth-box">
              <template v-if="oauthStatus?.connected">
                <el-tag size="small" type="success">{{ t('mcpPage.authorized') }}</el-tag>
                <span class="hint" v-if="oauthStatus?.expiresAt">
                  {{ t('mcpPage.expires') }}{{ new Date(oauthStatus.expiresAt).toLocaleString() }}
                </span>
                <el-tag v-if="oauthStatus?.refreshable" size="small" type="info">{{ t('mcpPage.refreshable') }}</el-tag>
              </template>
              <el-tag v-else size="small" type="info">{{ t('mcpPage.unauthorized') }}</el-tag>
              <el-button size="small" type="primary" :loading="oauthLoading" @click="startOAuth">
                {{ oauthStatus?.connected ? t('mcpPage.reauthorize') : t('mcpPage.authorize') }}
              </el-button>
              <el-button size="small" v-if="oauthStatus?.connected" @click="disconnectOAuth">{{ t('mcpPage.disconnect') }}</el-button>
            </div>
            <div class="hint">{{ t('mcpPage.oauthHint') }}</div>
          </el-form-item>
        </template>
        <el-form-item :label="t('mcpPage.tip')">
          <span class="hint">{{ t('mcpPage.tipText') }}</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">{{ t('mcpPage.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ t('mcpPage.save') }}</el-button>
      </template>
    </el-dialog>

    <!-- 同步工具 -->
    <el-dialog v-model="syncVisible" :title="`${t('mcpPage.syncTitle')}${syncServerName}`" width="640px">
      <div class="hint mb8">{{ t('mcpPage.syncHint') }}</div>
      <el-alert v-if="syncDiff" type="info" :closable="false" show-icon class="mb8"
                :title="`${t('mcpPage.diffPrefix')}${syncDiff.counts.added}${t('mcpPage.diffChanged')}${syncDiff.counts.changed}${t('mcpPage.diffUnchanged')}${syncDiff.counts.unchanged}${t('mcpPage.diffRemoved')}${syncDiff.counts.removed}`" />
      <el-alert v-if="syncDiff && syncDiff.removed.length" type="warning" :closable="false" show-icon class="mb8"
                :title="`${t('mcpPage.removedPrefix')}${syncDiff.removed.map((x: any) => x.name).join(', ')}${t('mcpPage.removedSuffix')}`" />
      <el-checkbox v-model="checkAll" @change="toggleAll">{{ t('mcpPage.checkAll') }}</el-checkbox>
      <el-table :data="discovered" size="small" max-height="360" v-loading="discovering">
        <el-table-column width="50">
          <template #default="{ row }">
            <el-checkbox v-model="row.checked" />
          </template>
        </el-table-column>
        <el-table-column prop="name" :label="t('mcpPage.toolName')" width="200" />
        <el-table-column :label="t('mcpPage.diff')" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.change === 'ADDED'" size="small" type="success">{{ t('mcpPage.added') }}</el-tag>
            <el-tag v-else-if="row.change === 'CHANGED'" size="small" type="warning">{{ t('mcpPage.changed') }}</el-tag>
            <span v-else class="hint">{{ t('mcpPage.unchanged') }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="description" :label="t('mcpPage.description')" min-width="220" show-overflow-tooltip />
      </el-table>
      <template #footer>
        <el-button @click="syncVisible = false">{{ t('mcpPage.cancel') }}</el-button>
        <el-button type="primary" :loading="syncing" @click="doSync">{{ t('mcpPage.syncSelected') }}</el-button>
      </template>
    </el-dialog>

    <!-- 已同步工具 -->
    <el-drawer v-model="toolsVisible" :title="`${t('mcpPage.syncedTitle')}${toolsServerName}`" size="560px">
      <el-table :data="tools" size="small">
        <el-table-column prop="name" :label="t('mcpPage.name')" width="160" />
        <el-table-column prop="toolKey" label="Tool Key" min-width="200" />
        <el-table-column :label="t('mcpPage.status')" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'ENABLED' ? 'success' : 'info'">
              {{ row.status === 'ENABLED' ? t('mcpPage.enabled') : t('mcpPage.disabled') }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!tools.length" :description="t('mcpPage.noSynced')" :image-size="60" />
    </el-drawer>

    <!-- MCP 资源 / 提示词发现（不落库，只读） -->
    <el-drawer v-model="capsVisible" :title="`${t('mcpPage.capsTitle')}${capsServerName}`" size="680px">
      <div v-loading="capsLoading">
        <el-alert v-if="capsWarnings.length" type="warning" :closable="false" show-icon class="mb12"
                  :title="capsWarnings.join('；')" />
        <el-tabs v-model="capsTab">
          <el-tab-pane :label="`${t('mcpPage.toolsTab')} (${caps.tools.length})`" name="tools">
            <el-table :data="caps.tools" size="small" max-height="420">
              <el-table-column prop="name" :label="t('mcpPage.name')" width="180" />
              <el-table-column prop="description" :label="t('mcpPage.description')" show-overflow-tooltip />
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="`${t('mcpPage.resourcesTab')} (${caps.resources.length})`" name="resources">
            <el-table :data="caps.resources" size="small" max-height="340">
              <el-table-column prop="name" :label="t('mcpPage.name')" width="150" />
              <el-table-column prop="uri" label="URI" min-width="220" show-overflow-tooltip />
              <el-table-column prop="mimeType" :label="t('mcpPage.type')" width="120" />
              <el-table-column :label="t('mcpPage.actions')" width="80">
                <template #default="{ row }">
                  <el-button size="small" text type="primary" @click="readResource(row)">{{ t('mcpPage.read') }}</el-button>
                </template>
              </el-table-column>
            </el-table>
            <div v-if="caps.resourceTemplates.length" class="mt8">
              <div class="hint">{{ t('mcpPage.resourceTemplates') }}</div>
              <el-table :data="caps.resourceTemplates" size="small" max-height="140">
                <el-table-column prop="name" :label="t('mcpPage.name')" width="150" />
                <el-table-column prop="uriTemplate" :label="t('mcpPage.uriTemplate')" show-overflow-tooltip />
              </el-table>
            </div>
          </el-tab-pane>
          <el-tab-pane :label="`${t('mcpPage.promptsTab')} (${caps.prompts.length})`" name="prompts">
            <el-table :data="caps.prompts" size="small" max-height="420">
              <el-table-column prop="name" :label="t('mcpPage.name')" width="180" />
              <el-table-column prop="description" :label="t('mcpPage.description')" show-overflow-tooltip />
            </el-table>
            <div class="hint mt8">{{ t('mcpPage.promptHint') }}</div>
          </el-tab-pane>
        </el-tabs>
      </div>
    </el-drawer>

    <el-dialog v-model="resourceVisible" :title="`${t('mcpPage.resourceTitle')}${resourceName}`" width="680px">
      <pre class="resource-pre">{{ resourceText }}</pre>
      <template #footer><el-button @click="resourceVisible = false">{{ t('mcpPage.close') }}</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'
import { useUserStore } from '../stores/user'

const { t } = useI18n()

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
  authHeader: 'X-API-Key', authValue: '', authToken: '', feishuAppId: '', feishuAppSecret: '',
  command: '', argsText: '[]', envText: ''
})

function applyPreset(type: string) {
  if (type === 'feishu') {
    Object.assign(editForm, {
      name: t('mcpPage.feishu'), endpoint: 'https://mcp.feishu.cn/mcp', transport: 'HTTP',
      authType: 'BEARER', headersText: '{}'
    })
  } else if (type === 'zhipu') {
    Object.assign(editForm, {
      name: t('mcpPage.zhipu'), endpoint: 'https://open.bigmodel.cn/api/mcp/sse', transport: 'SSE',
      authType: 'BEARER', headersText: '{}'
    })
  } else if (type === 'filesystem') {
    Object.assign(editForm, {
      name: t('mcpPage.filesystem'), transport: 'STDIO', command: 'npx',
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
const syncDiff = ref<any>(null)
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
    if (res.toolsChangedAt) capsWarnings.value.unshift(t('mcpPage.toolsChanged'))
  } catch (e: any) {
    caps.value = { tools: [], resources: [], resourceTemplates: [], prompts: [] }
    capsWarnings.value = [e?.response?.data?.message || t('mcpPage.capabilityFail')]
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
    ElMessage.error(e?.response?.data?.message || t('mcpPage.readFail'))
  }
}

onMounted(async () => {
  await load()
  if (route.query.oauth) {
    const status = String(route.query.oauth)
    const sid = Number(route.query.serverId)
    if (status === 'success') {
      ElMessage.success(t('mcpPage.oauthSuccess'))
      const row = list.value.find((r: any) => r.id === sid)
      if (row) await openEdit(row)
    } else {
      ElMessage.error(t('mcpPage.oauthFail') + (route.query.message || t('mcpPage.unknownError')))
    }
    const query = { ...route.query }
    delete query.oauth
    delete query.serverId
    delete query.message
    await router.replace({ query })
  }
  if (route.query.create === '1') {
    if (canManage.value) openCreate()
    else ElMessage.warning(t('mcpPage.noPermission'))
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
  if (s === 'ONLINE') return t('mcpPage.online')
  if (s === 'OFFLINE') return t('mcpPage.offline')
  if (s === 'ERROR') return t('mcpPage.error')
  return t('mcpPage.unchecked')
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
    authHeader: 'X-API-Key', authValue: '', authToken: '', feishuAppId: '', feishuAppSecret: '',
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
    feishuAppId: auth.appId || '',
    feishuAppSecret: '',
    command: detail.command || '',
    argsText: JSON.stringify(detail.args || []),
    envText: '{}'
  })
  editVisible.value = true
}

async function save(silent = false): Promise<boolean> {
  if (!editForm.name.trim()) { ElMessage.warning(t('mcpPage.nameRequired')); return false }
  if (editForm.transport === 'STDIO') {
    if (!editForm.command.trim()) { ElMessage.warning(t('mcpPage.commandRequired')); return false }
  } else if (!editForm.endpoint.trim()) {
    ElMessage.warning(t('mcpPage.addrRequired')); return false
  }
  saving.value = true
  try {
    const payload: any = { name: editForm.name.trim(), transport: editForm.transport }
    if (editForm.transport === 'STDIO') {
      let args: any
      try { args = JSON.parse(editForm.argsText || '[]') } catch { ElMessage.error(t('mcpPage.argsJson')); return false }
      if (!Array.isArray(args)) { ElMessage.error(t('mcpPage.argsArray')); return false }
      let env: any
      try { env = JSON.parse(editForm.envText || '{}') } catch { ElMessage.error(t('mcpPage.envJson')); return false }
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
      } else if (editForm.authType === 'FEISHU_APP') {
        if (!editForm.feishuAppId.trim()) { ElMessage.warning('App ID 必填'); return false }
        auth.appId = editForm.feishuAppId.trim()
        // Secret 留空 = 编辑时保持原值不覆盖
        if (editForm.feishuAppSecret.trim()) auth.appSecret = editForm.feishuAppSecret.trim()
      }
      let headers: any = {}
      try { headers = JSON.parse(editForm.headersText || '{}') } catch { ElMessage.error(t('mcpPage.headersJson')); return false }
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
      ElMessage.success(t('mcpPage.saved'))
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
    ElMessage.info(t('mcpPage.oauthOpened'))
  } catch (e: any) {
    popup?.close()
    ElMessage.error(e?.response?.data?.message || e?.message || t('mcpPage.oauthStartFail'))
  } finally {
    oauthLoading.value = false
  }
}

async function disconnectOAuth() {
  try {
    await ElMessageBox.confirm(t('mcpPage.disconnectConfirm'), t('mcpPage.disconnectTitle'))
  } catch { return }
  await http.post(`/mcp-servers/${editForm.id}/oauth/disconnect`)
  oauthStatus.value = { connected: false }
  ElMessage.success(t('mcpPage.disconnected'))
}

async function test(row: any) {
  testingId.value = row.id
  try {
    const res = await http.post(`/mcp-servers/${row.id}/test`) as any
    ElMessage[res.success ? 'success' : 'error'](
      res.success ? `${t('mcpPage.verifyOk')}${res.latencyMs}ms)` : `${t('mcpPage.verifyFail')}${res.message}`)
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
    const res = await http.get(`/mcp-servers/${row.id}/tools/diff`) as any
    syncDiff.value = res
    discovered.value = (res.tools || []).map((t: any) => ({ ...t, checked: true }))
    if (res.toolsChangedAt) ElMessage.warning(t('mcpPage.toolsChanged'))
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
  if (!names.length) { ElMessage.warning(t('mcpPage.selectTools')); return }
  syncing.value = true
  try {
    const res = await http.post(`/mcp-servers/${syncServerId.value}/sync`, { toolNames: names }) as any
    ElMessage.success(`${t('mcpPage.syncedCount')}${res.total}`)
    syncVisible.value = false
    syncDiff.value = null
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
    skills.length ? `${t('mcpPage.refsSkills')}${skills.join(', ')}` : ''
  ].filter(Boolean).join('；')
  await ElMessageBox.confirm(
    `${t('mcpPage.deleteConfirmPrefix')}${row.name}${t('mcpPage.deleteConfirmMid')}${refText ? t('mcpPage.deleteRefs') + refText + t('mcpPage.deleteRefs2') : ''}${t('mcpPage.deleteConfirmSuffix')}`,
    t('mcpPage.deleteTitle'), { type: 'warning', confirmButtonText: t('mcpPage.delete'), cancelButtonText: t('mcpPage.cancel') })
  await http.delete(`/mcp-servers/${row.id}`)
  ElMessage.success(t('mcpPage.deleted'))
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