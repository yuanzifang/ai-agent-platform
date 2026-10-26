<template>
  <div class="page">
    <div class="toolbar">
      <span class="page-title">{{ t('page.provider') }}</span>
      <div>
        <el-radio-group v-model="view" class="mr8">
          <el-radio-button value="admin">{{ t('providerPage.admin') }}</el-radio-button>
          <el-radio-button value="market">{{ t('providerPage.market') }}</el-radio-button>
        </el-radio-group>
        <el-button v-if="view === 'admin'" @click="modelCreateVisible = true">{{ t('providerPage.newModel') }}</el-button>
        <el-button v-if="view === 'admin'" type="primary" @click="openCreateProvider">{{ t('providerPage.newProvider') }}</el-button>
      </div>
    </div>

    <template v-if="view === 'market'">
      <div class="market-grid">
        <el-card v-for="m in models" :key="m.modelId" class="m-card" shadow="hover">
          <div class="m-head">
            <el-avatar :size="38" class="m-avatar">{{ (m.displayName || m.modelName).slice(0, 1) }}</el-avatar>
            <div>
              <div class="m-name">{{ m.displayName || m.modelName }}</div>
              <div class="m-provider">{{ m.providerName }} · {{ purposeText(m.purpose) }}</div>
              <div class="m-caps">
                <el-tag v-for="c in capabilityTags(m)" :key="c" size="small" effect="plain" class="cap-tag">{{ c }}</el-tag>
              </div>
            </div>
          </div>
          <div class="m-row">{{ t('providerPage.modelId') }}{{ m.modelName }}</div>
          <div class="m-row">{{ t('providerPage.inputPrice') }}{{ m.inputPrice ?? t('providerPage.notSet') }}{{ t('providerPage.perKToken') }}</div>
          <div class="m-row">{{ t('providerPage.outputPrice') }}{{ m.outputPrice ?? t('providerPage.notSet') }}{{ t('providerPage.perKToken') }}</div>
          <div class="m-row">{{ t('providerPage.context') }}{{ contextLabel(m.contextWindow) }}</div>
          <div class="m-foot">
            <el-tag :type="m.status === 'ENABLED' ? 'success' : 'info'" size="small">
              {{ m.status === 'ENABLED' ? t('providerPage.enabledUse') : t('providerPage.disabledUse') }}
            </el-tag>
          </div>
        </el-card>
        <el-empty v-if="models.length === 0" :description="t('providerPage.noModels')" />
      </div>
    </template>

    <template v-else>

    <el-card shadow="never" class="mb">
      <template #header>{{ t('providerPage.providerHeader') }}</template>
      <el-table :data="providers" size="small">
        <el-table-column :label="t('providerPage.name')" width="200">
          <template #default="{ row }">
            {{ row.name }}
            <el-tag v-if="row.credentialConfigured" size="small" type="success" class="ml6">{{ t('providerPage.keyConfigured') }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="providerType" :label="t('providerPage.type')" width="110" />
        <el-table-column prop="baseUrl" label="Base URL" min-width="200" />
        <el-table-column :label="t('providerPage.ownership')" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.preset ? 'info' : 'success'">{{ row.preset ? t('providerPage.preset') : t('providerPage.custom') }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" :label="t('providerPage.status')" width="90" />
        <el-table-column :label="t('providerPage.actions')" width="250">
          <template #default="{ row }">
            <el-button size="small" @click="test(row)">{{ t('providerPage.test') }}</el-button>
            <el-button size="small" type="primary" plain @click="sync(row)">{{ t('providerPage.sync') }}</el-button>
            <el-button v-if="!row.preset" size="small" text type="primary" @click="openCredential(row)">{{ t('providerPage.credential') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never">
      <template #header>{{ t('providerPage.modelHeader') }}</template>
      <el-table :data="models" size="small">
        <el-table-column prop="displayName" :label="t('providerPage.name')" width="180" />
        <el-table-column prop="modelName" :label="t('providerPage.modelName')" width="150" />
        <el-table-column prop="providerName" label="Provider" width="120" />
        <el-table-column :label="t('providerPage.purpose')" width="90">
          <template #default="{ row }">{{ purposeText(row.purpose) }}</template>
        </el-table-column>
        <el-table-column :label="t('providerPage.capabilitiesCol')" min-width="170">
          <template #default="{ row }">
            <el-tag v-for="c in capabilityTags(row)" :key="c" size="small" effect="plain" class="cap-tag">{{ c }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('providerPage.contextCol')" width="110">
          <template #default="{ row }">{{ contextLabel(row.contextWindow) }}</template>
        </el-table-column>
        <el-table-column :label="t('providerPage.enableDisable')" width="90">
          <template #default="{ row }">
            <el-switch :model-value="modelStatus(row)" @change="(v: any) => toggleModel(row, v)" />
          </template>
        </el-table-column>
        <el-table-column :label="t('providerPage.actions')" width="110">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="editPrice(row)">{{ t('providerPage.config') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    </template>

    <el-dialog v-model="createVisible" :title="t('providerPage.createTitle')" width="520px">
      <el-form label-width="90px">
        <el-form-item :label="t('providerPage.name')" required><el-input v-model="form.name" :placeholder="t('providerPage.namePh')" /></el-form-item>
        <el-form-item :label="t('providerPage.type')" required>
          <el-select v-model="form.providerType" style="width: 100%">
            <el-option v-for="t in ['OPENAI_COMPATIBLE','DEEPSEEK','ANTHROPIC','DASHSCOPE','OLLAMA','VOLC']" :key="t" :value="t" :label="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="Base URL" required>
          <el-input v-model="form.baseUrl" :placeholder="t('providerPage.baseUrlPh')" />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="form.apiKey" type="password" show-password
                    :placeholder="t('providerPage.keyPh')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">{{ t('providerPage.cancel') }}</el-button>
        <el-button type="primary" :loading="creating" @click="create">{{ t('providerPage.create') }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="credentialVisible" :title="t('providerPage.credentialTitle')" width="440px">
      <el-form label-width="90px">
        <el-form-item label="Provider">{{ credentialProvider.name }}</el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="credentialKey" type="password" show-password
                    :placeholder="t('providerPage.clearKeyPh')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="credentialVisible = false">{{ t('providerPage.cancel') }}</el-button>
        <el-button type="primary" :loading="savingCredential" @click="saveCredential">{{ t('providerPage.save') }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="modelCreateVisible" :title="t('providerPage.modelCreateTitle')" width="520px">
      <el-form label-width="110px">
        <el-form-item label="Provider" required>
          <el-select v-model="modelForm.providerId" style="width: 100%">
            <el-option v-for="p in providers" :key="p.id" :value="p.id" :label="p.name" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('providerPage.modelName')" required>
          <el-input v-model="modelForm.modelName" :placeholder="t('providerPage.modelNamePh')" />
        </el-form-item>
        <el-form-item :label="t('providerPage.displayName')">
          <el-input v-model="modelForm.displayName" />
        </el-form-item>
        <el-form-item :label="t('providerPage.purpose')">
          <el-select v-model="modelForm.purpose" style="width: 160px">
            <el-option :label="t('providerPage.chat')" value="CHAT" />
            <el-option :label="t('providerPage.platformOnly')" value="PLATFORM" />
            <el-option :label="t('providerPage.embedding')" value="EMBEDDING" />
            <el-option label="重排" value="RERANK" />
            <el-option label="视觉（图片理解）" value="VISION" />
            <el-option label="语音转文字" value="ASR" />
            <el-option label="文字识别（OCR）" value="OCR" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('providerPage.contextWindow')">
          <el-select v-model="modelForm.contextWindow" filterable allow-create style="width: 220px">
            <el-option v-for="c in contextOptions" :key="c.value" :value="c.value" :label="c.label" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="modelCreateVisible = false">{{ t('providerPage.cancel') }}</el-button>
        <el-button type="primary" :loading="modelCreating" @click="createModel">{{ t('providerPage.create') }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="priceVisible" :title="t('providerPage.priceTitle')" width="440px">
      <el-form label-width="130px">
        <el-form-item :label="t('providerPage.inputPriceLabel')">
          <el-input-number v-model="price.input" :min="0" :step="0.001" :precision="4" />
        </el-form-item>
        <el-form-item :label="t('providerPage.outputPriceLabel')">
          <el-input-number v-model="price.output" :min="0" :step="0.001" :precision="4" />
        </el-form-item>
        <el-form-item :label="t('providerPage.contextWindow')">
          <el-select v-model="price.contextWindow" filterable allow-create style="width: 220px">
            <el-option v-for="c in contextOptions" :key="c.value" :value="c.value" :label="c.label" />
          </el-select>
        </el-form-item>
        <el-divider content-position="left">{{ t('providerPage.reasoningTitle') }}</el-divider>
        <el-form-item :label="t('providerPage.reasoningSupported')">
          <el-switch v-model="price.reasoning" />
          <span class="hint">{{ t('providerPage.reasoningHint') }}</span>
        </el-form-item>
        <template v-if="price.reasoning">
          <el-form-item :label="t('providerPage.reasoningParam')">
            <el-select v-model="price.reasoningParam" style="width: 220px">
              <el-option value="effort" :label="t('providerPage.reasoningParamEffort')" />
              <el-option value="budget" :label="t('providerPage.reasoningParamBudget')" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('providerPage.reasoningLevels')">
            <el-select v-model="price.reasoningLevels" multiple style="width: 260px">
              <el-option v-for="lv in ['minimal', 'low', 'medium', 'high']" :key="lv" :value="lv"
                         :label="t('providerPage.reasoningLevelsMap.' + lv)" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('providerPage.defaultReasoning')">
            <el-select v-model="price.defaultReasoningEffort" style="width: 160px">
              <el-option v-for="lv in reasoningLevelOptions" :key="lv" :value="lv"
                         :label="t('providerPage.reasoningLevelsMap.' + lv)" />
            </el-select>
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="priceVisible = false">{{ t('providerPage.cancel') }}</el-button>
        <el-button type="primary" @click="savePrice">{{ t('providerPage.save') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api/http'

const { t } = useI18n()

const providers = ref<any[]>([])
const models = ref<any[]>([])
const disabled = ref<Set<number>>(new Set())
const createVisible = ref(false)
const creating = ref(false)
const priceVisible = ref(false)
const price = reactive({
  id: 0, input: 0, output: 0, contextWindow: 0,
  reasoning: false, reasoningParam: 'effort',
  reasoningLevels: ['low', 'medium', 'high'] as string[], defaultReasoningEffort: 'medium'
})
const modelCreateVisible = ref(false)
const modelCreating = ref(false)
const modelForm = reactive({ providerId: null as number | null, modelName: '', displayName: '', purpose: 'CHAT', contextWindow: 0 })
const contextOptions = [
  { value: 0, label: t('providerPage.defaultOption') },
  { value: 32768, label: '32K' },
  { value: 65536, label: '64K' },
  { value: 131072, label: '128K' },
  { value: 262144, label: '256K' },
  { value: 1048576, label: '1M' }
]
function contextLabel(v?: number) {
  const c = Number(v || 0)
  if (c <= 0) return t('providerPage.notSet')
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
    ElMessage.success(credentialKey.value ? t('providerPage.keySaved') : t('providerPage.keyCleared'))
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
  if (!form.name.trim() || !form.baseUrl.trim()) { ElMessage.warning(t('providerPage.nameUrlRequired')); return }
  creating.value = true
  try {
    await http.post('/provider', { ...form })
    ElMessage.success(t('providerPage.providerCreated'))
    createVisible.value = false
    Object.assign(form, { name: '', providerType: 'OPENAI_COMPATIBLE', baseUrl: '', apiKey: '' })
    await load()
  } finally {
    creating.value = false
  }
}

async function test(row: any) {
  const res = await http.post(`/provider/${row.id}/test`) as any
  ElMessage.success(res.message || `${t('providerPage.connectivityOk')}${res.latencyMs ?? '?'}ms`)
}

async function sync(row: any) {
  const res = await http.post(`/provider/${row.id}/models/sync`) as any
  ElMessage.success(`${t('providerPage.syncDone')}${res.total ?? 0}${t('providerPage.syncModels')}${res.enabled ?? 0}${t('providerPage.syncEnabled')}`)
  await load()
}

const CAP_TEXT = {
  multimodal: '多模态', reasoning: '推理', functionCall: '函数调用',
  vision: '图片理解', ocr: '文字识别(OCR)', asr: '语音转文字'
}
/** capabilities JSON → 可读能力标签（只展示为 true 的键）。 */
function capabilityTags(row: any): string[] {
  const tags: string[] = []
  try {
    const caps = typeof row.capabilities === 'string'
      ? JSON.parse(row.capabilities) : (row.capabilities || {})
    for (const [k, v] of Object.entries(caps)) {
      if (v === true && (CAP_TEXT as any)[k]) tags.push((CAP_TEXT as any)[k])
    }
  } catch { /* 忽略坏 JSON */ }
  return tags.length ? tags : ['—']
}

function purposeText(p?: string) {
  return ({ CHAT: '对话', EMBEDDING: '向量', RERANK: '重排', VISION: '视觉' } as any)[p || ''] || p || '-'
}

function modelStatus(row: any) {
  return !disabled.value.has(row.modelId)
}

async function toggleModel(row: any, enabled: boolean) {
  await http.put(`/models/${row.modelId}`, { status: enabled ? 'ENABLED' : 'DISABLED' })
  if (enabled) disabled.value.delete(row.modelId)
  else disabled.value.add(row.modelId)
  disabled.value = new Set(disabled.value)
  ElMessage.success(enabled ? t('providerPage.modelEnabled') : t('providerPage.modelDisabled'))
}

async function createModel() {
  if (!modelForm.providerId || !modelForm.modelName.trim()) {
    ElMessage.warning(t('providerPage.providerModelRequired')); return
  }
  modelCreating.value = true
  try {
    await http.post(`/provider/${modelForm.providerId}/models`, {
      modelName: modelForm.modelName.trim(),
      displayName: modelForm.displayName,
      purpose: modelForm.purpose,
      contextWindow: modelForm.contextWindow
    })
    ElMessage.success(t('providerPage.modelCreated'))
    modelCreateVisible.value = false
    modelForm.modelName = ''
    modelForm.displayName = ''
    await load()
  } finally {
    modelCreating.value = false
  }
}

function parseJson(value: any): Record<string, any> {
  if (!value) return {}
  if (typeof value === 'object') return value
  try { return JSON.parse(String(value)) } catch { return {} }
}

const reasoningLevelOptions = computed(() =>
  price.reasoningLevels.length ? price.reasoningLevels : ['low', 'medium', 'high'])

function editPrice(row: any) {
  price.id = row.modelId
  price.input = Number(row.inputPrice ?? 0)
  price.output = Number(row.outputPrice ?? 0)
  price.contextWindow = Number(row.contextWindow ?? 0)
  const caps = parseJson(row.capabilities)
  price.reasoning = caps.reasoning === true
  price.reasoningParam = caps.reasoningParam === 'budget' ? 'budget' : 'effort'
  price.reasoningLevels = Array.isArray(caps.reasoningLevels) && caps.reasoningLevels.length
    ? caps.reasoningLevels.map((x: any) => String(x))
    : ['low', 'medium', 'high']
  price.defaultReasoningEffort = String(parseJson(row.defaultParams).reasoningEffort || 'medium')
  priceVisible.value = true
}

async function savePrice() {
  await http.put(`/models/${price.id}`, {
    inputPrice: price.input,
    outputPrice: price.output,
    contextWindow: price.contextWindow,
    reasoning: price.reasoning,
    reasoningParam: price.reasoningParam,
    reasoningLevels: price.reasoning ? price.reasoningLevels : [],
    defaultReasoningEffort: price.defaultReasoningEffort
  })
  ElMessage.success(t('providerPage.modelConfigSaved'))
  priceVisible.value = false
  await load()
}
</script>

<style scoped>
.cap-tag { margin-right: 4px; }
.hint { color: #909399; font-size: 12px; margin-left: 8px; }
.m-caps { margin-top: 6px; display: flex; flex-wrap: wrap; gap: 4px; }
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