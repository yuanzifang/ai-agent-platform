<template>
  <div class="page">
    <div class="toolbar">
      <span class="title">工具管理</span>
      <span class="sub">内置工具只读；API 工具支持请求配置/鉴权/超时重试；MCP 工具由 MCP Server 同步</span>
      <div class="spacer" />
      <el-input v-model="keyword" placeholder="搜索名称/描述" clearable style="width: 190px"
                @keyup.enter="load" @clear="load" />
      <el-select v-model="type" placeholder="类型" clearable style="width: 110px" @change="load">
        <el-option label="内置" value="BUILTIN" />
        <el-option label="API" value="API" />
        <el-option label="MCP" value="MCP" />
      </el-select>
      <el-button @click="load">搜索</el-button>
      <el-button type="primary" :disabled="!canManage" @click="openCreate">新建 API 工具</el-button>
      <el-button :disabled="!canManage" @click="$router.push('/mcp-servers?create=1')">添加 MCP Server</el-button>
    </div>

    <el-alert v-if="!canManage" type="info" :closable="false" show-icon class="mb12"
              title="只读模式：新建/测试工具需要 Owner 或管理员权限（tool:manage）" />

    <el-table :data="list" size="small" v-loading="loading">
      <template #empty>
        <el-empty description="暂无工具">
          <el-button type="primary" :disabled="!canManage" @click="openCreate">新建 API 工具</el-button>
          <el-button :disabled="!canManage" @click="$router.push('/mcp-servers?create=1')">添加 MCP Server</el-button>
        </el-empty>
      </template>
      <el-table-column label="名称" width="200">
        <template #default="{ row }">
          {{ row.name }}
          <el-tag size="small"
                  :type="row.toolType === 'BUILTIN' ? 'info' : (row.toolType === 'MCP' ? 'warning' : 'success')">
            {{ row.toolType === 'BUILTIN' ? '内置' : (row.toolType === 'MCP' ? 'MCP' : 'API') }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="category" label="分类" width="90" />
      <el-table-column prop="description" label="描述" min-width="240" show-overflow-tooltip />
      <el-table-column label="超时/重试" width="110">
        <template #default="{ row }">{{ row.timeoutMs }}ms / {{ row.retryTimes }} 次</template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'ENABLED' ? 'success' : 'info'">
            {{ row.status === 'ENABLED' ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="270">
        <template #default="{ row }">
          <el-button size="small" text :disabled="!canManage" @click="openTest(row)">测试</el-button>
          <template v-if="canManage && !row.isBuiltin && row.toolType !== 'MCP'">
            <el-button size="small" text @click="openEdit(row)">编辑</el-button>
            <el-button size="small" text @click="publish(row)">发布</el-button>
            <el-button size="small" text @click="openVersions(row)">版本</el-button>
            <el-button size="small" text type="danger" @click="remove(row)">删除</el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新建/编辑 -->
    <el-dialog v-model="editVisible" :title="editForm.id ? '编辑工具' : '新建 API 工具'" width="760px">
      <el-alert v-if="!editForm.id" type="info" :closable="false" show-icon class="mb12"
                title="这里创建 API 工具；MCP 工具请到「MCP Server」添加服务后同步生成。" />
      <el-form label-width="96px">
        <el-form-item label="名称" required>
          <el-input v-model="editForm.name" maxlength="128" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="editForm.description" maxlength="512"
                    placeholder="描述工具能力，LLM 依据该描述决定何时调用" />
        </el-form-item>
        <el-form-item label="分类">
          <el-input v-model="editForm.category" style="width: 220px" />
        </el-form-item>
        <el-form-item label="标签">
          <el-input v-model="editForm.tags" placeholder="逗号分隔" />
        </el-form-item>
        <el-form-item label="API 地址" required>
          <el-input v-model="editForm.url" placeholder="https://api.example.com/search" />
        </el-form-item>
        <el-form-item label="请求方法">
          <el-select v-model="editForm.method" style="width: 140px">
            <el-option v-for="m in ['GET','POST','PUT','PATCH','DELETE']" :key="m" :value="m" :label="m" />
          </el-select>
        </el-form-item>
        <el-form-item label="请求头">
          <el-input v-model="editForm.headersText" type="textarea" :rows="2"
                    placeholder='JSON 对象，如 {"Content-Type":"application/json"}' />
        </el-form-item>
        <el-form-item label="参数 Schema">
          <div class="param-editor">
            <el-table :data="editForm.params" size="small">
              <el-table-column label="参数名" width="130">
                <template #default="{ row }"><el-input v-model="row.name" size="small" /></template>
              </el-table-column>
              <el-table-column label="类型" width="100">
                <template #default="{ row }">
                  <el-select v-model="row.type" size="small">
                    <el-option v-for="t in ['string','number','boolean','integer']" :key="t" :value="t" :label="t" />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="位置" width="90">
                <template #default="{ row }">
                  <el-select v-model="row.in" size="small">
                    <el-option label="query" value="query" />
                    <el-option label="body" value="body" />
                    <el-option label="path" value="path" />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="必填" width="60">
                <template #default="{ row }"><el-checkbox v-model="row.required" /></template>
              </el-table-column>
              <el-table-column label="说明">
                <template #default="{ row }"><el-input v-model="row.description" size="small" /></template>
              </el-table-column>
              <el-table-column width="52">
                <template #default="{ $index }">
                  <el-button text type="danger" size="small" @click="editForm.params.splice($index, 1)">删</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-button size="small" class="mt8" @click="addParam">＋ 添加参数</el-button>
          </div>
        </el-form-item>
        <el-form-item label="请求体模板">
          <el-input v-model="editForm.bodyTemplate" type="textarea" :rows="3"
                    placeholder='POST/PUT 时使用，支持 {{参数名}} 占位，如 {"q":"{{query}}"}' />
        </el-form-item>
        <el-form-item label="响应解析路径">
          <el-input v-model="editForm.responsePath" placeholder="如 data.items（点路径，可含数组下标）" />
        </el-form-item>
        <el-form-item label="鉴权方式">
          <el-select v-model="editForm.authType" style="width: 140px">
            <el-option label="无" value="NONE" />
            <el-option label="API Key" value="API_KEY" />
            <el-option label="Bearer" value="BEARER" />
            <el-option label="Basic" value="BASIC" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="editForm.authType === 'API_KEY'" label="Key 配置">
          <el-input v-model="editForm.authHeader" placeholder="Header 名，默认 X-API-Key" style="width: 200px" />
          <el-input v-model="editForm.authValue" type="password" show-password placeholder="Key（****** 表示不修改）"
                    style="width: 260px; margin-left: 8px" />
        </el-form-item>
        <el-form-item v-if="editForm.authType === 'BEARER'" label="Token">
          <el-input v-model="editForm.authToken" type="password" show-password
                    placeholder="Bearer Token（****** 表示不修改）" />
        </el-form-item>
        <el-form-item v-if="editForm.authType === 'BASIC'" label="Basic">
          <el-input v-model="editForm.authUser" placeholder="用户名" style="width: 200px" />
          <el-input v-model="editForm.authPassword" type="password" show-password
                    placeholder="密码（****** 表示不修改）" style="width: 260px; margin-left: 8px" />
        </el-form-item>
        <el-form-item label="超时(ms)">
          <el-input-number v-model="editForm.timeoutMs" :min="500" :max="120000" :step="500" />
        </el-form-item>
        <el-form-item label="重试次数">
          <el-input-number v-model="editForm.retryTimes" :min="0" :max="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 测试 -->
    <el-dialog v-model="testVisible" :title="`测试工具：${testForm.name}`" width="640px">
      <el-input v-model="testForm.argsJson" type="textarea" :rows="4"
                placeholder='调用参数 JSON，如 {"expression":"(12+3)*4"}' />
      <div class="mt8">
        <el-button type="primary" :loading="testing" @click="runTest">执行测试</el-button>
        <span class="hint">测试不写入正式 Trace</span>
      </div>
      <div v-if="testForm.output" class="test-result">
        <div class="test-label">输出（{{ testForm.latencyMs }}ms）</div>
        <div class="test-output">{{ testForm.output }}</div>
      </div>
    </el-dialog>

    <!-- 版本 -->
    <el-drawer v-model="versionsVisible" title="版本历史" size="420px">
      <el-table :data="versions" size="small">
        <el-table-column prop="version" label="版本" width="80" />
        <el-table-column prop="createdAt" label="时间" />
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="rollback(row.version)">回滚</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="versions.length === 0" description="暂无版本，请先发布" :image-size="60" />
    </el-drawer>
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
const keyword = ref('')
const type = ref('')

const editVisible = ref(false)
const saving = ref(false)
const editForm = reactive({
  id: 0, name: '', description: '', category: '', tags: '',
  url: '', method: 'GET', headersText: '{\n  "Content-Type": "application/json"\n}',
  params: [] as any[], bodyTemplate: '', responsePath: '',
  authType: 'NONE', authHeader: 'X-API-Key', authValue: '', authToken: '',
  authUser: '', authPassword: '', timeoutMs: 10000, retryTimes: 2
})

const testVisible = ref(false)
const testing = ref(false)
const testForm = reactive({ id: 0, name: '', argsJson: '{}', output: '', latencyMs: 0 })

const versionsVisible = ref(false)
const versions = ref<any[]>([])
const versionToolId = ref(0)

onMounted(async () => {
  await load()
  if (route.query.create === '1') {
    if (canManage.value) openCreate()
    else ElMessage.warning('当前账号无新建工具权限')
    const query = { ...route.query }
    delete query.create
    router.replace({ query })
  }
})

async function load() {
  loading.value = true
  try {
    const res = await http.get('/tools', {
      params: { keyword: keyword.value || undefined, type: type.value || undefined }
    }) as any
    list.value = res?.list || []
  } finally {
    loading.value = false
  }
}

function addParam() {
  editForm.params.push({ name: '', type: 'string', in: 'query', required: false, description: '' })
}

function openCreate() {
  Object.assign(editForm, {
    id: 0, name: '', description: '', category: '', tags: '',
    url: '', method: 'GET', headersText: '{\n  "Content-Type": "application/json"\n}',
    params: [], bodyTemplate: '', responsePath: '',
    authType: 'NONE', authHeader: 'X-API-Key', authValue: '', authToken: '',
    authUser: '', authPassword: '', timeoutMs: 10000, retryTimes: 2
  })
  editVisible.value = true
}

async function openEdit(row: any) {
  const detail = await http.get(`/tools/${row.id}`) as any
  const cfg = detail.apiConfig || {}
  const auth = cfg.auth || {}
  Object.assign(editForm, {
    id: detail.id,
    name: detail.name || '',
    description: detail.description || '',
    category: detail.category || '',
    tags: (detail.tags || []).join(','),
    url: cfg.url || '',
    method: cfg.method || 'GET',
    headersText: JSON.stringify(cfg.headers || {}, null, 2),
    params: (cfg.params || []).map((p: any) => ({
      name: p.name, type: p.type || 'string', in: p.in || 'query',
      required: !!p.required, description: p.description || ''
    })),
    bodyTemplate: cfg.bodyTemplate || '',
    responsePath: cfg.responsePath || '',
    authType: auth.type || 'NONE',
    authHeader: auth.header || 'X-API-Key',
    authValue: auth.value || '',
    authToken: auth.token || '',
    authUser: auth.username || '',
    authPassword: auth.password || '',
    timeoutMs: detail.timeoutMs ?? 10000,
    retryTimes: detail.retryTimes ?? 2
  })
  editVisible.value = true
}

function parseHeaders() {
  const text = editForm.headersText?.trim()
  if (!text) return {}
  try {
    return JSON.parse(text)
  } catch {
    ElMessage.error('请求头不是合法 JSON')
    return null
  }
}

async function save() {
  if (!editForm.name.trim()) { ElMessage.warning('名称必填'); return }
  if (!editForm.url.trim()) { ElMessage.warning('API 地址必填'); return }
  const headers = parseHeaders()
  if (headers === null) return
  const auth: any = { type: editForm.authType }
  if (editForm.authType === 'API_KEY') {
    auth.header = editForm.authHeader || 'X-API-Key'
    auth.value = editForm.authValue
  } else if (editForm.authType === 'BEARER') {
    auth.token = editForm.authToken
  } else if (editForm.authType === 'BASIC') {
    auth.username = editForm.authUser
    auth.password = editForm.authPassword
  }
  saving.value = true
  try {
    const payload = {
      name: editForm.name.trim(),
      description: editForm.description,
      category: editForm.category,
      tags: editForm.tags.split(/[,，]/).map(t => t.trim()).filter(Boolean),
      toolType: 'API',
      timeoutMs: editForm.timeoutMs,
      retryTimes: editForm.retryTimes,
      apiConfig: {
        url: editForm.url.trim(),
        method: editForm.method,
        headers,
        params: editForm.params.filter(p => p.name),
        bodyTemplate: editForm.bodyTemplate || undefined,
        responsePath: editForm.responsePath || undefined,
        auth
      }
    }
    if (editForm.id) {
      await http.put(`/tools/${editForm.id}`, payload)
    } else {
      await http.post('/tools', payload)
    }
    ElMessage.success('已保存')
    editVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

function openTest(row: any) {
  Object.assign(testForm, { id: row.id, name: row.name, argsJson: '{}', output: '', latencyMs: 0 })
  testVisible.value = true
}

async function runTest() {
  testing.value = true
  try {
    const res = await http.post(`/tools/${testForm.id}/test`, { argsJson: testForm.argsJson }) as any
    testForm.output = res?.output || ''
    testForm.latencyMs = res?.latencyMs || 0
  } finally {
    testing.value = false
  }
}

async function publish(row: any) {
  const res = await http.post(`/tools/${row.id}/publish`) as any
  ElMessage.success(`已发布 ${res.version}`)
}

async function openVersions(row: any) {
  versionToolId.value = row.id
  versions.value = await http.get(`/tools/${row.id}/versions`) as any
  versionsVisible.value = true
}

async function rollback(version: string) {
  await ElMessageBox.confirm(`回滚到 ${version} 将生成新版本，继续？`, '回滚确认', { type: 'warning' })
  await http.post(`/tools/${versionToolId.value}/versions/${version}/rollback`)
  ElMessage.success('已回滚')
  versionsVisible.value = false
  await load()
}

async function remove(row: any) {
  const refs = await http.get(`/tools/${row.id}/references`) as any
  const refsText = [
    (refs?.agents || []).length ? `Agent：${refs.agents.join('、')}` : '',
    (refs?.skills || []).length ? `技能：${refs.skills.join('、')}` : ''
  ].filter(Boolean).join('；')
  const extra = refsText ? `该工具被引用（${refsText}），将软删除并显示「已失效」。` : '该工具未被引用，将永久删除。'
  await ElMessageBox.confirm(`确认删除工具「${row.name}」？${extra}`, '删除工具',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  const res = await http.delete(`/tools/${row.id}`) as any
  ElMessage.success(res?.softDeleted ? '已软删除（已失效）' : '已删除')
  await load()
}
</script>

<style scoped>
.page { background: #fff; border: 1px solid #e4e7ed; border-radius: 6px; padding: 14px; }
.toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
.title { font-weight: 600; font-size: 15px; white-space: nowrap; flex-shrink: 0; }
.sub { color: #909399; font-size: 12px; margin-left: 8px; }
.spacer { flex: 1; }
.hint { color: #909399; font-size: 12px; margin-left: 8px; }
.mb12 { margin-bottom: 12px; }
.mt8 { margin-top: 8px; }
.param-editor { width: 100%; }
.test-result { margin-top: 12px; border: 1px solid #ebeef5; border-radius: 6px; padding: 10px; }
.test-label { font-size: 12px; color: #909399; margin-bottom: 4px; }
.test-output { white-space: pre-wrap; font-size: 13px; line-height: 1.6; word-break: break-all; }
</style>
