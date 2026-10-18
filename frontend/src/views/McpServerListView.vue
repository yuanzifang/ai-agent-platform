<template>
  <div class="page">
    <div class="toolbar">
      <span class="title">MCP Server</span>
      <span class="sub">接入 MCP 服务，发现并同步工具；同步后的工具可在 Agent 中绑定</span>
      <div class="spacer" />
      <el-button @click="load">刷新</el-button>
      <el-button v-if="canManage" type="primary" @click="openCreate">接入 Server</el-button>
    </div>

    <el-empty v-if="!loading && list.length === 0" description="尚未接入 MCP Server">
      <el-button v-if="canManage" type="primary" @click="openCreate">接入第一个 MCP Server</el-button>
      <span v-else class="hint">请联系租户管理员接入</span>
    </el-empty>
    <el-table v-else :data="list" size="small" v-loading="loading">
      <el-table-column prop="name" label="名称" width="180" />
      <el-table-column prop="endpoint" label="地址" min-width="240" show-overflow-tooltip />
      <el-table-column prop="authType" label="鉴权" width="100" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="healthType(row.healthStatus)">
            {{ healthText(row.healthStatus) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="toolCount" label="工具数" width="80" />
      <el-table-column label="最后同步" width="160">
        <template #default="{ row }">{{ fmt(row.lastSyncAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="300">
        <template #default="{ row }">
          <el-button v-if="canManage" size="small" text @click="test(row)">测试</el-button>
          <el-button v-if="canManage" size="small" text type="primary" @click="openSync(row)">同步工具</el-button>
          <el-button size="small" text @click="openTools(row)">已同步</el-button>
          <el-button v-if="canManage" size="small" text @click="openEdit(row)">编辑</el-button>
          <el-button v-if="canManage" size="small" text type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 接入/编辑 -->
    <el-dialog v-model="editVisible" :title="editForm.id ? '编辑 MCP Server' : '接入 MCP Server'" width="600px">
      <el-form label-width="90px">
        <el-form-item label="名称" required>
          <el-input v-model="editForm.name" maxlength="128" placeholder="如：内部工具服务" />
        </el-form-item>
        <el-form-item label="地址" required>
          <el-input v-model="editForm.endpoint" placeholder="http://localhost:18081/mcp" />
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
        <el-form-item v-if="editForm.authType === 'BEARER' || editForm.authType === 'OAUTH'" label="Token">
          <el-input v-model="editForm.authToken" type="password" show-password
                    placeholder="Token（****** 表示不修改）" />
        </el-form-item>
        <el-form-item label="提示">
          <span class="hint">编辑地址或鉴权后，需重新「同步工具」</span>
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
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'
import { useUserStore } from '../stores/user'

const userStore = useUserStore()
const canManage = computed(() => {
  const perms = userStore.userInfo?.permissions || []
  return userStore.userInfo?.isPlatformAdmin || perms.includes('tool:manage')
})

const list = ref<any[]>([])
const loading = ref(false)

const editVisible = ref(false)
const saving = ref(false)
const editForm = reactive({
  id: 0, name: '', endpoint: '', authType: 'NONE',
  authHeader: 'X-API-Key', authValue: '', authToken: ''
})

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

onMounted(load)

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
    id: 0, name: '', endpoint: '', authType: 'NONE',
    authHeader: 'X-API-Key', authValue: '', authToken: ''
  })
  editVisible.value = true
}

async function openEdit(row: any) {
  const detail = await http.get(`/mcp-servers/${row.id}`) as any
  const auth = detail.auth || {}
  Object.assign(editForm, {
    id: detail.id,
    name: detail.name || '',
    endpoint: detail.endpoint || '',
    authType: detail.authType || 'NONE',
    authHeader: auth.header || 'X-API-Key',
    authValue: auth.value || '',
    authToken: auth.token || ''
  })
  editVisible.value = true
}

async function save() {
  if (!editForm.name.trim() || !editForm.endpoint.trim()) { ElMessage.warning('名称与地址必填'); return }
  const auth: any = { type: editForm.authType }
  if (editForm.authType === 'API_KEY') {
    auth.header = editForm.authHeader || 'X-API-Key'
    auth.value = editForm.authValue
  } else if (editForm.authType === 'BEARER' || editForm.authType === 'OAUTH') {
    auth.token = editForm.authToken
  }
  saving.value = true
  try {
    const payload = {
      name: editForm.name.trim(), endpoint: editForm.endpoint.trim(),
      authType: editForm.authType, auth
    }
    if (editForm.id) await http.put(`/mcp-servers/${editForm.id}`, payload)
    else await http.post('/mcp-servers', payload)
    ElMessage.success('已保存')
    editVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function test(row: any) {
  const res = await http.post(`/mcp-servers/${row.id}/test`) as any
  ElMessage[res.success ? 'success' : 'error'](res.message + (res.success ? `（${res.latencyMs}ms）` : ''))
  await load()
}

async function openSync(row: any) {
  syncServerId.value = row.id
  syncServerName.value = row.name
  syncVisible.value = true
  discovering.value = true
  try {
    const res = await http.get(`/mcp-servers/${row.id}/discover`) as any
    discovered.value = (res.tools || []).map((t: any) => ({ ...t, checked: true }))
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
.toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.title { font-weight: 600; font-size: 15px; }
.sub { color: #909399; font-size: 12px; margin-left: 8px; }
.spacer { flex: 1; }
.hint { color: #909399; font-size: 12px; }
.mb8 { margin-bottom: 8px; }
</style>
