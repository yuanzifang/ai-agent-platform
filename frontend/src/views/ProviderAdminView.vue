<template>
  <div class="page">
    <div class="toolbar">
      <span class="page-title">模型接入</span>
      <div>
        <el-radio-group v-model="view" class="mr8">
          <el-radio-button value="admin">接入管理</el-radio-button>
          <el-radio-button value="market">模型广场</el-radio-button>
        </el-radio-group>
        <el-button v-if="view === 'admin'" @click="modelCreateVisible = true">新增模型</el-button>
        <el-button v-if="view === 'admin'" type="primary" @click="openCreateProvider">接入新 Provider</el-button>
      </div>
    </div>

    <template v-if="view === 'market'">
      <div class="market-grid">
        <el-card v-for="m in models" :key="m.modelId" class="m-card" shadow="hover">
          <div class="m-head">
            <el-avatar :size="38" class="m-avatar">{{ (m.displayName || m.modelName).slice(0, 1) }}</el-avatar>
            <div>
              <div class="m-name">{{ m.displayName || m.modelName }}</div>
              <div class="m-provider">{{ m.providerName }} · {{ m.purpose }}</div>
            </div>
          </div>
          <div class="m-row">模型 ID：{{ m.modelName }}</div>
          <div class="m-row">输入价：{{ m.inputPrice ?? '未设置' }} 元/千Token</div>
          <div class="m-row">输出价：{{ m.outputPrice ?? '未设置' }} 元/千Token</div>
          <div class="m-row">上下文：{{ contextLabel(m.contextWindow) }}</div>
          <div class="m-foot">
            <el-tag :type="m.status === 'ENABLED' ? 'success' : 'info'" size="small">
              {{ m.status === 'ENABLED' ? '可使用' : '已停用' }}
            </el-tag>
          </div>
        </el-card>
        <el-empty v-if="models.length === 0" description="暂无模型" />
      </div>
    </template>

    <template v-else>

    <el-card shadow="never" class="mb">
      <template #header>Provider（平台预置 + 租户自建）</template>
      <el-table :data="providers" size="small">
        <el-table-column label="名称" width="200">
          <template #default="{ row }">
            {{ row.name }}
            <el-tag v-if="row.credentialConfigured" size="small" type="success" class="ml6">密钥已配置</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="providerType" label="类型" width="110" />
        <el-table-column prop="baseUrl" label="Base URL" min-width="200" />
        <el-table-column label="归属" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.preset ? 'info' : 'success'">{{ row.preset ? '平台预置' : '租户自建' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="90" />
        <el-table-column label="操作" width="250">
          <template #default="{ row }">
            <el-button size="small" @click="test(row)">测试</el-button>
            <el-button size="small" type="primary" plain @click="sync(row)">同步模型</el-button>
            <el-button v-if="!row.preset" size="small" text type="primary" @click="openCredential(row)">密钥</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never">
      <template #header>模型列表（默认定价为预填，计价权威为计费规则）</template>
      <el-table :data="models" size="small">
        <el-table-column prop="displayName" label="名称" width="180" />
        <el-table-column prop="modelName" label="模型 ID" width="150" />
        <el-table-column prop="providerName" label="Provider" width="120" />
        <el-table-column prop="purpose" label="用途" width="90" />
        <el-table-column label="上下文" width="110">
          <template #default="{ row }">{{ contextLabel(row.contextWindow) }}</template>
        </el-table-column>
        <el-table-column label="启停" width="90">
          <template #default="{ row }">
            <el-switch :model-value="modelStatus(row)" @change="(v: any) => toggleModel(row, v)" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="editPrice(row)">配置</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    </template>

    <el-dialog v-model="createVisible" title="接入新 Provider" width="520px">
      <el-form label-width="90px">
        <el-form-item label="名称" required><el-input v-model="form.name" placeholder="如：智谱" /></el-form-item>
        <el-form-item label="类型" required>
          <el-select v-model="form.providerType" style="width: 100%">
            <el-option v-for="t in ['OPENAI_COMPATIBLE','DEEPSEEK','DASHSCOPE','OLLAMA','VOLC']" :key="t" :value="t" :label="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="Base URL" required>
          <el-input v-model="form.baseUrl" placeholder="https://api.example.com（OpenAI 兼容）" />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="form.apiKey" type="password" show-password
                    placeholder="sk-...（Ollama/内网代理可留空；加密存储）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="create">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="credentialVisible" title="配置 Provider API Key" width="440px">
      <el-form label-width="90px">
        <el-form-item label="Provider">{{ credentialProvider.name }}</el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="credentialKey" type="password" show-password
                    placeholder="留空并保存表示清除密钥" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="credentialVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingCredential" @click="saveCredential">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="modelCreateVisible" title="新增模型" width="520px">
      <el-form label-width="110px">
        <el-form-item label="Provider" required>
          <el-select v-model="modelForm.providerId" style="width: 100%">
            <el-option v-for="p in providers" :key="p.id" :value="p.id" :label="p.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="模型 ID" required>
          <el-input v-model="modelForm.modelName" placeholder="如 deepseek-chat" />
        </el-form-item>
        <el-form-item label="显示名称">
          <el-input v-model="modelForm.displayName" />
        </el-form-item>
        <el-form-item label="用途">
          <el-select v-model="modelForm.purpose" style="width: 160px">
            <el-option label="对话 CHAT" value="CHAT" />
            <el-option label="嵌入 EMBEDDING" value="EMBEDDING" />
          </el-select>
        </el-form-item>
        <el-form-item label="上下文窗口">
          <el-select v-model="modelForm.contextWindow" filterable allow-create style="width: 220px">
            <el-option v-for="c in contextOptions" :key="c.value" :value="c.value" :label="c.label" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="modelCreateVisible = false">取消</el-button>
        <el-button type="primary" :loading="modelCreating" @click="createModel">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="priceVisible" title="模型配置" width="440px">
      <el-form label-width="130px">
        <el-form-item label="输入 元/千Token">
          <el-input-number v-model="price.input" :min="0" :step="0.001" :precision="4" />
        </el-form-item>
        <el-form-item label="输出 元/千Token">
          <el-input-number v-model="price.output" :min="0" :step="0.001" :precision="4" />
        </el-form-item>
        <el-form-item label="上下文窗口">
          <el-select v-model="price.contextWindow" filterable allow-create style="width: 220px">
            <el-option v-for="c in contextOptions" :key="c.value" :value="c.value" :label="c.label" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="priceVisible = false">取消</el-button>
        <el-button type="primary" @click="savePrice">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api/http'

const providers = ref<any[]>([])
const models = ref<any[]>([])
const disabled = ref<Set<number>>(new Set())
const createVisible = ref(false)
const creating = ref(false)
const priceVisible = ref(false)
const price = reactive({ id: 0, input: 0, output: 0, contextWindow: 0 })
const modelCreateVisible = ref(false)
const modelCreating = ref(false)
const modelForm = reactive({ providerId: null as number | null, modelName: '', displayName: '', purpose: 'CHAT', contextWindow: 0 })
const contextOptions = [
  { value: 0, label: '未设置（默认）' },
  { value: 32768, label: '32K' },
  { value: 65536, label: '64K' },
  { value: 131072, label: '128K' },
  { value: 262144, label: '256K' },
  { value: 1048576, label: '1M' }
]
function contextLabel(v?: number) {
  const c = Number(v || 0)
  if (c <= 0) return '未设置'
  return c >= 1048576 ? (c / 1048576) + 'M' : (c / 1024) + 'K'
}
const form = reactive({ name: '', providerType: 'OPENAI_COMPATIBLE', baseUrl: '', apiKey: '' })
const view = ref('admin')
const credentialVisible = ref(false)
const credentialProvider = ref<any>({})
const credentialKey = ref('')
const savingCredential = ref(false)

function openCredential(row: any) {
  credentialProvider.value = row
  credentialKey.value = ''
  credentialVisible.value = true
}

async function saveCredential() {
  savingCredential.value = true
  try {
    await http.put(`/provider/${credentialProvider.value.id}/credential`, { apiKey: credentialKey.value })
    ElMessage.success(credentialKey.value ? '密钥已加密保存' : '已清除密钥')
    credentialVisible.value = false
    await load()
  } finally {
    savingCredential.value = false
  }
}

onMounted(load)

async function load() {
  providers.value = await http.get('/provider') as any
  models.value = await http.get('/provider/models') as any
  disabled.value = new Set(models.value.filter((m: any) => m.status === 'DISABLED').map((m: any) => m.modelId))
}

function openCreateProvider() {
  Object.assign(form, { name: '', providerType: 'OPENAI_COMPATIBLE', baseUrl: '', apiKey: '' })
  createVisible.value = true
}

async function create() {
  if (!form.name.trim() || !form.baseUrl.trim()) { ElMessage.warning('名称和 Base URL 必填'); return }
  creating.value = true
  try {
    await http.post('/provider', { ...form })
    ElMessage.success('Provider 已创建；可测试连通性并同步模型')
    createVisible.value = false
    Object.assign(form, { name: '', providerType: 'OPENAI_COMPATIBLE', baseUrl: '', apiKey: '' })
    await load()
  } finally {
    creating.value = false
  }
}

async function test(row: any) {
  const res = await http.post(`/provider/${row.id}/test`) as any
  ElMessage.success(res.message || `连通正常，耗时 ${res.latencyMs ?? '?'}ms`)
}

async function sync(row: any) {
  const res = await http.post(`/provider/${row.id}/models/sync`) as any
  ElMessage.success(`同步完成：模型 ${res.total ?? 0} 个，启用 ${res.enabled ?? 0} 个`)
  await load()
}

function modelStatus(row: any) {
  return !disabled.value.has(row.modelId)
}

async function toggleModel(row: any, enabled: boolean) {
  await http.put(`/models/${row.modelId}`, { status: enabled ? 'ENABLED' : 'DISABLED' })
  if (enabled) disabled.value.delete(row.modelId)
  else disabled.value.add(row.modelId)
  disabled.value = new Set(disabled.value)
  ElMessage.success(enabled ? '已启用' : '已停用')
}

async function createModel() {
  if (!modelForm.providerId || !modelForm.modelName.trim()) {
    ElMessage.warning('Provider 与模型 ID 必填'); return
  }
  modelCreating.value = true
  try {
    await http.post(`/provider/${modelForm.providerId}/models`, {
      modelName: modelForm.modelName.trim(),
      displayName: modelForm.displayName,
      purpose: modelForm.purpose,
      contextWindow: modelForm.contextWindow
    })
    ElMessage.success('模型已创建')
    modelCreateVisible.value = false
    modelForm.modelName = ''
    modelForm.displayName = ''
    await load()
  } finally {
    modelCreating.value = false
  }
}

function editPrice(row: any) {
  price.id = row.modelId
  price.input = Number(row.inputPrice ?? 0)
  price.output = Number(row.outputPrice ?? 0)
  price.contextWindow = Number(row.contextWindow ?? 0)
  priceVisible.value = true
}

async function savePrice() {
  await http.put(`/models/${price.id}`, {
    inputPrice: price.input, outputPrice: price.output, contextWindow: price.contextWindow
  })
  ElMessage.success('已保存模型配置')
  priceVisible.value = false
}
</script>

<style scoped>
.page { max-width: 1100px; margin: 0 auto; }
.mr8 { margin-right: 8px; }
.market-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 14px; }
.m-card { cursor: default; }
.m-head { display: flex; gap: 10px; align-items: center; margin-bottom: 10px; }
.m-avatar { background: #409eff; color: #fff; font-weight: 600; }
.m-name { font-weight: 600; }
.m-provider { font-size: 12px; color: #909399; }
.m-row { font-size: 13px; color: #606266; margin: 4px 0; }
.m-foot { margin-top: 8px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; }
.page-title { font-size: 16px; font-weight: 600; }
.mb { margin-bottom: 14px; }
.ml6 { margin-left: 6px; }
</style>
