<template>
  <div class="page">
    <div class="toolbar">
      <span class="title">{{ t('page.tools') }}</span>
      <span class="sub">{{ t('tool.sub') }}</span>
      <div class="spacer" />
      <el-input v-model="keyword" :placeholder="t('tool.searchPh')" clearable style="width: 190px"
                @keyup.enter="load" @clear="load" />
      <el-select v-model="type" :placeholder="t('tool.type')" clearable style="width: 110px" @change="load">
        <el-option :label="t('tool.builtin')" value="BUILTIN" />
        <el-option label="API" value="API" />
        <el-option label="MCP" value="MCP" />
      </el-select>
      <el-button @click="load">{{ t('tool.search') }}</el-button>
      <el-button type="primary" :disabled="!canManage" @click="openCreate">{{ t('tool.createApi') }}</el-button>
      <el-button :disabled="!canManage" @click="$router.push('/mcp-servers?create=1')">{{ t('tool.addMcp') }}</el-button>
    </div>

    <el-alert v-if="!canManage" type="info" :closable="false" show-icon class="mb12"
              :title="t('tool.readonly')" />

    <el-table :data="list" size="small" v-loading="loading">
      <template #empty>
        <el-empty :description="t('tool.empty')">
          <el-button type="primary" :disabled="!canManage" @click="openCreate">{{ t('tool.createApi') }}</el-button>
          <el-button :disabled="!canManage" @click="$router.push('/mcp-servers?create=1')">{{ t('tool.addMcp') }}</el-button>
        </el-empty>
      </template>
      <el-table-column :label="t('tool.name')" width="200">
        <template #default="{ row }">
          {{ row.name }}
          <el-tag size="small"
                  :type="row.toolType === 'BUILTIN' ? 'info' : (row.toolType === 'MCP' ? 'warning' : 'success')">
            {{ row.toolType === 'BUILTIN' ? t('tool.builtin') : (row.toolType === 'MCP' ? 'MCP' : 'API') }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="category" :label="t('tool.category')" width="90" />
      <el-table-column prop="description" :label="t('tool.desc')" min-width="240" show-overflow-tooltip />
      <el-table-column :label="t('tool.timeoutRetry')" width="110">
        <template #default="{ row }">{{ row.timeoutMs }}ms / {{ row.retryTimes }} {{ t('tool.times') }}</template>
      </el-table-column>
      <el-table-column :label="t('tool.status')" width="80">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'ENABLED' ? 'success' : 'info'">
            {{ row.status === 'ENABLED' ? t('tool.enabled') : t('tool.disabled') }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('tool.actions')" width="270">
        <template #default="{ row }">
          <el-button size="small" text :disabled="!canManage" @click="openTest(row)">{{ t('tool.test') }}</el-button>
          <template v-if="canManage && !row.isBuiltin && row.toolType !== 'MCP'">
            <el-button size="small" text @click="openEdit(row)">{{ t('tool.edit') }}</el-button>
            <el-button size="small" text @click="publish(row)">{{ t('tool.publish') }}</el-button>
            <el-button size="small" text @click="openVersions(row)">{{ t('tool.versions') }}</el-button>
            <el-button size="small" text type="danger" @click="remove(row)">{{ t('tool.delete') }}</el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新建/编辑 -->
    <el-dialog v-model="editVisible" :title="editForm.id ? t('tool.editTitle') : t('tool.newApi')" width="760px">
      <el-alert v-if="!editForm.id" type="info" :closable="false" show-icon class="mb12"
                :title="t('tool.mcpHint')" />
      <el-form label-width="96px">
        <el-form-item :label="t('tool.name')" required>
          <el-input v-model="editForm.name" maxlength="128" />
        </el-form-item>
        <el-form-item :label="t('tool.desc')">
          <el-input v-model="editForm.description" maxlength="512"
                    :placeholder="t('tool.descPh')" />
        </el-form-item>
        <el-form-item :label="t('tool.category')">
          <el-input v-model="editForm.category" style="width: 220px" />
        </el-form-item>
        <el-form-item :label="t('tool.tags')">
          <el-input v-model="editForm.tags" :placeholder="t('tool.tagsPh')" />
        </el-form-item>
        <el-form-item :label="t('tool.apiUrl')" required>
          <el-input v-model="editForm.url" placeholder="https://api.example.com/search" />
        </el-form-item>
        <el-form-item :label="t('tool.method')">
          <el-select v-model="editForm.method" style="width: 140px">
            <el-option v-for="m in ['GET','POST','PUT','PATCH','DELETE']" :key="m" :value="m" :label="m" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('tool.headers')">
          <el-input v-model="editForm.headersText" type="textarea" :rows="2"
                    :placeholder="t('tool.headersPh')" />
        </el-form-item>
        <el-form-item :label="t('tool.paramSchema')">
          <div class="param-editor">
            <el-table :data="editForm.params" size="small">
              <el-table-column :label="t('tool.paramName')" width="130">
                <template #default="{ row }"><el-input v-model="row.name" size="small" /></template>
              </el-table-column>
              <el-table-column :label="t('tool.typeLabel')" width="100">
                <template #default="{ row }">
                  <el-select v-model="row.type" size="small">
                    <el-option v-for="t in ['string','number','boolean','integer']" :key="t" :value="t" :label="t" />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column :label="t('tool.location')" width="90">
                <template #default="{ row }">
                  <el-select v-model="row.in" size="small">
                    <el-option label="query" value="query" />
                    <el-option label="body" value="body" />
                    <el-option label="path" value="path" />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column :label="t('tool.required')" width="60">
                <template #default="{ row }"><el-checkbox v-model="row.required" /></template>
              </el-table-column>
              <el-table-column :label="t('tool.note')">
                <template #default="{ row }"><el-input v-model="row.description" size="small" /></template>
              </el-table-column>
              <el-table-column width="52">
                <template #default="{ $index }">
                  <el-button text type="danger" size="small" @click="editForm.params.splice($index, 1)">{{ t('tool.remove') }}</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-button size="small" class="mt8" @click="addParam">{{ t('tool.addParam') }}</el-button>
          </div>
        </el-form-item>
        <el-form-item :label="t('tool.bodyTemplate')">
          <el-input v-model="editForm.bodyTemplate" type="textarea" :rows="3"
                    :placeholder="t('tool.bodyPh')" />
        </el-form-item>
        <el-form-item :label="t('tool.responsePath')">
          <el-input v-model="editForm.responsePath" :placeholder="t('tool.responsePh')" />
        </el-form-item>
        <el-form-item :label="t('tool.authType')">
          <el-select v-model="editForm.authType" style="width: 140px">
            <el-option :label="t('tool.none')" value="NONE" />
            <el-option label="API Key" value="API_KEY" />
            <el-option label="Bearer" value="BEARER" />
            <el-option label="Basic" value="BASIC" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="editForm.authType === 'API_KEY'" :label="t('tool.keyConfig')">
          <el-input v-model="editForm.authHeader" :placeholder="t('tool.headerNamePh')" style="width: 200px" />
          <el-input v-model="editForm.authValue" type="password" show-password :placeholder="t('tool.keyPh')"
                    style="width: 260px; margin-left: 8px" />
        </el-form-item>
        <el-form-item v-if="editForm.authType === 'BEARER'" label="Token">
          <el-input v-model="editForm.authToken" type="password" show-password
                    :placeholder="t('tool.bearerPh')" />
        </el-form-item>
        <el-form-item v-if="editForm.authType === 'BASIC'" label="Basic">
          <el-input v-model="editForm.authUser" :placeholder="t('tool.username')" style="width: 200px" />
          <el-input v-model="editForm.authPassword" type="password" show-password
                    :placeholder="t('tool.passwordPh')" style="width: 260px; margin-left: 8px" />
        </el-form-item>
        <el-form-item :label="t('tool.timeoutMs')">
          <el-input-number v-model="editForm.timeoutMs" :min="500" :max="120000" :step="500" />
        </el-form-item>
        <el-form-item :label="t('tool.retryTimes')">
          <el-input-number v-model="editForm.retryTimes" :min="0" :max="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">{{ t('tool.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ t('tool.save') }}</el-button>
      </template>
    </el-dialog>

    <!-- 测试 -->
    <el-dialog v-model="testVisible" :title="`${t('tool.testTitle')}${testForm.name}`" width="640px">
      <el-input v-model="testForm.argsJson" type="textarea" :rows="4"
                :placeholder="t('tool.testPh')" />
      <div class="mt8">
        <el-button type="primary" :loading="testing" @click="runTest">{{ t('tool.runTest') }}</el-button>
        <span class="hint">{{ t('tool.testHint') }}</span>
      </div>
      <div v-if="testForm.output" class="test-result">
        <div class="test-label">{{ t('tool.output') }}（{{ testForm.latencyMs }}ms）</div>
        <div class="test-output">{{ testForm.output }}</div>
      </div>
    </el-dialog>

    <!-- 版本 -->
    <el-drawer v-model="versionsVisible" :title="t('tool.versionHistory')" size="420px">
      <el-table :data="versions" size="small">
        <el-table-column prop="version" :label="t('tool.version')" width="80" />
        <el-table-column prop="createdAt" :label="t('tool.time')" />
        <el-table-column :label="t('tool.actions')" width="90">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="rollback(row.version)">{{ t('tool.rollback') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="versions.length === 0" :description="t('tool.noVersions')" :image-size="60" />
    </el-drawer>
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
    else ElMessage.warning(t('tool.noPermission'))
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
    ElMessage.error(t('tool.invalidJson'))
    return null
  }
}

async function save() {
  if (!editForm.name.trim()) { ElMessage.warning(t('tool.nameRequired')); return }
  if (!editForm.url.trim()) { ElMessage.warning(t('tool.urlRequired')); return }
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
    ElMessage.success(t('tool.saved'))
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
  ElMessage.success(`${t('tool.published')} ${res.version}`)
}

async function openVersions(row: any) {
  versionToolId.value = row.id
  versions.value = await http.get(`/tools/${row.id}/versions`) as any
  versionsVisible.value = true
}

async function rollback(version: string) {
  await ElMessageBox.confirm(`${t('tool.rollbackConfirm')}${version}${t('tool.rollbackSuffix')}`, t('tool.rollbackTitle'), { type: 'warning' })
  await http.post(`/tools/${versionToolId.value}/versions/${version}/rollback`)
  ElMessage.success(t('tool.rolledBack'))
  versionsVisible.value = false
  await load()
}

async function remove(row: any) {
  const refs = await http.get(`/tools/${row.id}/references`) as any
  const refsText = [
    (refs?.agents || []).length ? `${t('tool.refsAgent')}${refs.agents.join('、')}` : '',
    (refs?.skills || []).length ? `${t('tool.refsSkill')}${refs.skills.join('、')}` : ''
  ].filter(Boolean).join('；')
  const extra = refsText ? `${t('tool.refsSoft')}${refsText}${t('tool.refsSoftSuffix')}` : t('tool.refsNone')
  await ElMessageBox.confirm(`${t('tool.deleteConfirm')}${row.name}${t('tool.deleteSuffix')}${extra}`, t('tool.deleteTool'),
    { type: 'warning', confirmButtonText: t('tool.delete'), cancelButtonText: t('tool.cancel') })
  const res = await http.delete(`/tools/${row.id}`) as any
  ElMessage.success(res?.softDeleted ? t('tool.softDeleted') : t('tool.deleted'))
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