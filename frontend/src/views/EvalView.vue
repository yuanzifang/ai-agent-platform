<template>
  <div class="page">
    <div class="toolbar">
      <el-button text @click="$router.push('/agents')">
        <el-icon><ArrowLeft /></el-icon> {{ t('evalPage.back') }}
      </el-button>
      <span class="page-title">{{ t('page.eval') }}</span>
      <el-radio-group v-model="tab">
        <el-radio-button value="once">{{ t('evalPage.once') }}</el-radio-button>
        <el-radio-button value="online">{{ t('evalPage.online') }}</el-radio-button>
        <el-radio-button value="pre">{{ t('evalPage.pre') }}</el-radio-button>
        <el-radio-button value="dataset">{{ t('evalPage.dataset') }}</el-radio-button>
      </el-radio-group>
    </div>

    <!-- 一次性评测 -->
    <template v-if="tab === 'once'">
      <div class="sub-bar">
        <span></span>
        <div>
          <el-select v-model="agentId" :placeholder="t('evalPage.selectAgent')" size="small" style="width: 180px; margin-right: 8px"
                     @change="loadTasks">
            <el-option v-for="a in agents" :key="a.id" :value="a.id" :label="a.name" />
          </el-select>
          <el-button type="primary" size="small" @click="taskDialog = true">{{ t('evalPage.startEval') }}</el-button>
        </div>
      </div>
      <el-card shadow="never">
        <el-table :data="tasks" size="small" @row-click="openTask">
          <el-table-column prop="name" :label="t('evalPage.taskName')" min-width="150" />
          <el-table-column prop="datasetName" :label="t('evalPage.datasetName')" width="130" />
          <el-table-column :label="t('evalPage.progress')" width="90">
            <template #default="{ row }">{{ row.completedCalls ?? 0 }}/{{ row.estimatedCalls ?? '-' }}</template>
          </el-table-column>
          <el-table-column :label="t('evalPage.status')" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'DONE' ? 'success' : row.status === 'RUNNING' ? 'warning' : row.status === 'FAILED' ? 'danger' : 'info'">
                {{ statusText(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('evalPage.avgScore')" width="90">
            <template #default="{ row }">{{ row.avgScore != null ? (row.avgScore * 100).toFixed(1) : '-' }}</template>
          </el-table-column>
          <el-table-column :label="t('evalPage.passRate')" width="90">
            <template #default="{ row }">{{ row.passRate != null ? (row.passRate * 100).toFixed(0) + '%' : '-' }}</template>
          </el-table-column>
          <el-table-column :label="t('evalPage.time')" width="140">
            <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
          </el-table-column>
        </el-table>
        <el-empty v-if="tasks.length === 0" :description="t('evalPage.emptyTasks')" :image-size="60" />
      </el-card>
    </template>

    <!-- 在线监控 -->
    <template v-else-if="tab === 'online'">
      <div class="sub-bar">
        <span class="hint-inline">{{ t('evalPage.onlineHint') }}</span>
        <div>
          <el-select v-model="agentId" :placeholder="t('evalPage.selectAgent')" size="small" style="width: 160px; margin-right: 8px"
                     @change="loadTasks">
            <el-option v-for="a in agents" :key="a.id" :value="a.id" :label="a.name" />
          </el-select>
          <el-button type="primary" size="small" :loading="onlineRunning" @click="runOnline">{{ t('evalPage.sampleNow') }}</el-button>
        </div>
      </div>
      <el-card shadow="never">
        <el-table :data="onlineTasks" size="small">
          <el-table-column prop="name" :label="t('evalPage.taskName')" min-width="150" />
          <el-table-column :label="t('evalPage.type')" width="110">
            <template #default="{ row }">
              <el-tag size="small">{{ row.evalType }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('evalPage.sampleProgress')" width="90">
            <template #default="{ row }">{{ row.completedCalls ?? 0 }}/{{ row.estimatedCalls ?? '-' }}</template>
          </el-table-column>
          <el-table-column :label="t('evalPage.status')" width="90">
            <template #default="{ row }">{{ statusText(row.status) }}</template>
          </el-table-column>
          <el-table-column :label="t('evalPage.avgScore')" width="90">
            <template #default="{ row }">{{ row.avgScore != null ? (row.avgScore * 100).toFixed(1) : '-' }}</template>
          </el-table-column>
          <el-table-column :label="t('evalPage.time')" width="140">
            <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
          </el-table-column>
        </el-table>
        <el-empty v-if="onlineTasks.length === 0" :description="t('evalPage.emptyOnline')" :image-size="60" />
      </el-card>
    </template>

    <!-- 预发布评测 -->
    <template v-else-if="tab === 'pre'">
      <div class="sub-bar">
        <span class="hint-inline">{{ t('evalPage.preHint') }}</span>
        <div>
          <el-select v-model="agentId" :placeholder="t('evalPage.selectAgent')" size="small" style="width: 160px; margin-right: 8px"
                     @change="loadTasks">
            <el-option v-for="a in agents" :key="a.id" :value="a.id" :label="a.name" />
          </el-select>
          <el-button type="primary" size="small" :loading="preRunning" @click="runPreRelease">{{ t('evalPage.runPre') }}</el-button>
        </div>
      </div>
      <el-card shadow="never">
        <el-table :data="preTasks" size="small">
          <el-table-column prop="name" :label="t('evalPage.taskName')" min-width="150" />
          <el-table-column prop="datasetName" :label="t('evalPage.datasetName')" width="140" />
          <el-table-column :label="t('evalPage.passRate')" width="100">
            <template #default="{ row }">{{ row.passRate != null ? (row.passRate * 100).toFixed(0) + '%' : '-' }}</template>
          </el-table-column>
          <el-table-column :label="t('evalPage.status')" width="90">
            <template #default="{ row }">{{ statusText(row.status) }}</template>
          </el-table-column>
          <el-table-column :label="t('evalPage.time')" width="150">
            <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
          </el-table-column>
        </el-table>
        <el-empty v-if="preTasks.length === 0" :description="t('evalPage.emptyPre')" :image-size="60" />
      </el-card>
    </template>

    <!-- 数据集管理 -->
    <template v-else>
      <div class="sub-bar">
        <span></span>
        <el-button type="primary" size="small" @click="dsDialog = true">{{ t('evalPage.newDs') }}</el-button>
      </div>
      <el-card shadow="never">
        <el-table :data="datasets" size="small">
          <el-table-column prop="name" :label="t('evalPage.name')" width="180" />
          <el-table-column prop="description" :label="t('evalPage.desc')" min-width="180" />
          <el-table-column prop="itemCount" :label="t('evalPage.itemCount')" width="80" />
          <el-table-column :label="t('evalPage.boundAgent')" width="110">
            <template #default="{ row }">{{ agentName(row.agentId) }}</template>
          </el-table-column>
          <el-table-column :label="t('evalPage.actions')" width="180">
            <template #default="{ row }">
              <el-button size="small" @click="openItems(row)">{{ t('evalPage.sampleMgmt') }}</el-button>
              <el-button size="small" type="danger" plain @click="removeDs(row)">{{ t('evalPage.delete') }}</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </template>

    <!-- 开始评测弹窗 -->
    <el-dialog v-model="taskDialog" :title="t('evalPage.startTitle')" width="480px">
      <el-form label-width="90px">
        <el-form-item :label="t('evalPage.taskName')">
          <el-input v-model="taskForm.name" :placeholder="t('evalPage.taskNamePh')" />
        </el-form-item>
        <el-form-item label="Agent" required>
          <el-select v-model="taskForm.agentId" style="width: 100%">
            <el-option v-for="a in agents" :key="a.id" :value="a.id" :label="a.name" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('evalPage.datasetName')" required>
          <el-select v-model="taskForm.datasetId" style="width: 100%">
            <el-option v-for="d in datasets" :key="d.id" :value="d.id" :label="`${d.name} (${d.itemCount}${t('evalPage.sampleSuffix')})`" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('evalPage.judgeModel')">
          <el-select v-model="taskForm.judgeModel" style="width: 100%">
            <el-option v-for="m in models" :key="m.modelName" :value="m.modelName" :label="modelLabel(m)" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('evalPage.threshold')">
          <el-slider v-model="taskForm.passThreshold" :min="0.3" :max="0.95" :step="0.05" style="width: 90%" />
        </el-form-item>
      </el-form>
      <div class="dialog-hint">{{ t('evalPage.flowHint') }}</div>
      <template #footer>
        <el-button @click="taskDialog = false">{{ t('evalPage.cancel') }}</el-button>
        <el-button type="primary" :loading="creating" @click="createTask">{{ t('evalPage.submit') }}</el-button>
      </template>
    </el-dialog>

    <!-- 任务详情抽屉 -->
    <el-drawer v-model="taskVisible" :title="t('evalPage.resultTitle') + (current.name || '')" size="70%">
      <div class="sum-cards">
        <div class="sc"><div class="k">{{ t('evalPage.status') }}</div><div class="v">{{ statusText(current.status) }}</div></div>
        <div class="sc"><div class="k">{{ t('evalPage.avgScore') }}</div><div class="v">{{ current.avgScore != null ? (current.avgScore * 100).toFixed(1) : '-' }}</div></div>
        <div class="sc"><div class="k">{{ t('evalPage.passRateShort') }}</div><div class="v">{{ current.passRate != null ? (current.passRate * 100).toFixed(0) + '%' : '-' }}</div></div>
        <div class="sc"><div class="k">{{ t('evalPage.judgeModel') }}</div><div class="v small">{{ current.judgeModel }}</div></div>
      </div>
      <el-table :data="current.results || []" size="small">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="expand">
              <div><b>{{ t('evalPage.expected') }}</b>{{ row.expectedAnswer || t('evalPage.none') }}</div>
              <div class="mt4"><b>{{ t('evalPage.actual') }}</b>{{ row.answer }}</div>
              <div class="mt4"><b>{{ t('evalPage.judgeReason') }}</b>{{ row.judgeReason }}</div>
              <div class="mt4 trace-link">Trace: {{ row.traceId }}</div>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('evalPage.question')" min-width="140">
          <template #default="{ row }">{{ clip(row.question, 24) }}</template>
        </el-table-column>
        <el-table-column :label="t('evalPage.answer')" min-width="180">
          <template #default="{ row }">{{ clip(row.answer, 32) }}</template>
        </el-table-column>
        <el-table-column :label="t('evalPage.score')" width="80">
          <template #default="{ row }">{{ row.score != null ? (row.score * 100).toFixed(0) : '-' }}</template>
        </el-table-column>
        <el-table-column :label="t('evalPage.pass')" width="70">
          <template #default="{ row }">
            <el-tag size="small" :type="row.passed ? 'success' : 'danger'">{{ row.passed ? t('evalPage.passed') : t('evalPage.failed') }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('evalPage.latency')" width="80">
          <template #default="{ row }">{{ row.latencyMs }}ms</template>
        </el-table-column>
        <el-table-column prop="tokens" label="Token" width="80" />
      </el-table>
    </el-drawer>

    <!-- 样本管理抽屉 -->
    <el-drawer v-model="itemsVisible" :title="t('evalPage.sampleTitle') + (currentDs.name || '')" size="640px">
      <div class="import-box">
        <el-input v-model="jsonl" type="textarea" :rows="3"
                  :placeholder="t('evalPage.importPh')" />
        <el-button size="small" class="mt8" @click="importItems">{{ t('evalPage.import') }}</el-button>
      </div>
      <el-divider />
      <div class="item-form">
        <el-input v-model="itemForm.question" :placeholder="t('evalPage.question')" size="small" />
        <el-input v-model="itemForm.expectedAnswer" :placeholder="t('evalPage.expectedAnswer')" size="small" />
        <el-input v-model="itemForm.scoreCriteria" :placeholder="t('evalPage.criteriaPh')" size="small" />
        <el-button size="small" type="primary" @click="addItem">{{ t('evalPage.addSample') }}</el-button>
      </div>
      <el-table :data="items" size="small" class="mt8">
        <el-table-column :label="t('evalPage.question')" min-width="160">
          <template #default="{ row }">{{ clip(row.question, 30) }}</template>
        </el-table-column>
        <el-table-column :label="t('evalPage.expectedAnswer')" min-width="160">
          <template #default="{ row }">{{ clip(row.expectedAnswer, 30) }}</template>
        </el-table-column>
        <el-table-column width="60">
          <template #default="{ row }">
            <el-button size="small" text type="danger" @click="removeItem(row)">{{ t('evalPage.delete') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <!-- 新建数据集 -->
    <el-dialog v-model="dsDialog" :title="t('evalPage.dsNew')" width="460px">
      <el-form label-width="80px">
        <el-form-item :label="t('evalPage.name')" required><el-input v-model="dsForm.name" /></el-form-item>
        <el-form-item :label="t('evalPage.desc')"><el-input v-model="dsForm.description" type="textarea" :rows="2" /></el-form-item>
        <el-form-item :label="t('evalPage.bindAgent')">
          <el-select v-model="dsForm.agentId" clearable :placeholder="t('evalPage.dsAgentPh')" style="width: 100%">
            <el-option v-for="a in agents" :key="a.id" :value="a.id" :label="a.name" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dsDialog = false">{{ t('evalPage.cancel') }}</el-button>
        <el-button type="primary" @click="createDs">{{ t('evalPage.create') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import http from '../api/http'

const { t } = useI18n()

const route = useRoute()
const tab = ref('once')
const agentId = ref<number | null>(route.params.agentId ? Number(route.params.agentId) : null)
const agents = ref<any[]>([])
const models = ref<any[]>([])
const datasets = ref<any[]>([])
function modelLabel(m: any) {
  const name = m.displayName || m.modelName
  const c = Number(m.contextWindow || 0)
  return c > 0 ? `${name} (${c >= 1048576 ? (c / 1048576) + 'M' : (c / 1024) + 'K'}${t('evalPage.contextSuffix')})` : name
}
const tasks = ref<any[]>([])
const taskDialog = ref(false)
const creating = ref(false)
const taskForm = reactive({ name: '', agentId: null as number | null, datasetId: null as number | null, judgeModel: 'deepseek-flash', passThreshold: 0.6 })
const taskVisible = ref(false)
const current = reactive<any>({})
const itemsVisible = ref(false)
const currentDs = reactive<any>({})
const items = ref<any[]>([])
const jsonl = ref('')
const itemForm = reactive({ question: '', expectedAnswer: '', scoreCriteria: '' })
const dsDialog = ref(false)
const dsForm = reactive({ name: '', description: '', agentId: null as number | null })

onMounted(async () => {
  const [a, m, ds] = await Promise.all([
    http.get('/agent', { params: { pageSize: 100 } }) as any,
    http.get('/provider/models') as any,
    http.get('/eval/datasets') as any
  ])
  agents.value = a.list || []
  models.value = (m || []).filter((x: any) => !x.purpose || String(x.purpose).toUpperCase() === 'CHAT')
  datasets.value = ds || []
  if (!taskForm.agentId && agents.value.length) taskForm.agentId = agents.value[0].id
  if (agentId.value) taskForm.agentId = agentId.value
  await loadTasks()
})

async function loadTasks() {
  const data = await http.get('/eval/tasks', { params: { agentId: agentId.value || undefined } }) as any
  const all = data.list || []
  tasks.value = all.filter((t: any) => t.evalType === 'ONCE')
  onlineTasks.value = all.filter((t: any) => t.evalType === 'ONLINE' || t.evalType === 'FEEDBACK')
  preTasks.value = all.filter((t: any) => t.evalType === 'PRE_RELEASE')
}

const onlineTasks = ref<any[]>([])
const preTasks = ref<any[]>([])
const onlineRunning = ref(false)
const preRunning = ref(false)

async function runOnline() {
  if (!agentId.value && agents.value.length) agentId.value = agents.value[0].id
  if (!agentId.value) { ElMessage.warning(t('evalPage.selectAgentWarn')); return }
  onlineRunning.value = true
  try {
    const res = await http.post('/eval/online', null, { params: { agentId: agentId.value, sampleSize: 10 } }) as any
    ElMessage.success(`${t('evalPage.sampledToast')}${res.sampled ?? 0}${t('evalPage.traces')}`)
    setTimeout(loadTasks, 2500)
  } finally {
    onlineRunning.value = false
  }
}

async function runPreRelease() {
  if (!agentId.value && agents.value.length) agentId.value = agents.value[0].id
  if (!agentId.value) { ElMessage.warning(t('evalPage.selectAgentWarn')); return }
  preRunning.value = true
  try {
    const res = await http.post('/eval/pre-release', null, { params: { agentId: agentId.value } }) as any
    ElMessage.success(`${t('evalPage.preSubmitted')}${res.taskId}${t('evalPage.preSubmittedSuffix')}`)
    setTimeout(loadTasks, 2500)
  } finally {
    preRunning.value = false
  }
}

async function createTask() {
  if (!taskForm.agentId || !taskForm.datasetId) { ElMessage.warning(t('evalPage.selectAgentDs')); return }
  creating.value = true
  try {
    await http.post('/eval/tasks', { ...taskForm })
    ElMessage.success(t('evalPage.taskSubmitted'))
    taskDialog.value = false
    setTimeout(loadTasks, 1500)
  } finally {
    creating.value = false
  }
}

async function openTask(row: any) {
  Object.assign(current, await http.get(`/eval/tasks/${row.id}`) as any)
  taskVisible.value = true
}

async function createDs() {
  if (!dsForm.name.trim()) { ElMessage.warning(t('evalPage.nameRequired')); return }
  await http.post('/eval/datasets', { ...dsForm })
  ElMessage.success(t('evalPage.created'))
  dsDialog.value = false
  datasets.value = await http.get('/eval/datasets') as any
}

async function removeDs(row: any) {
  await ElMessageBox.confirm(`${t('evalPage.deleteDsPrefix')}${row.name}${t('evalPage.deleteDsMid')}`, t('evalPage.confirm'), { type: 'warning' })
  await http.delete(`/eval/datasets/${row.id}`)
  datasets.value = await http.get('/eval/datasets') as any
}

async function openItems(row: any) {
  Object.assign(currentDs, row)
  itemsVisible.value = true
  items.value = await http.get(`/eval/datasets/${row.id}/items`) as any
}

async function addItem() {
  if (!itemForm.question.trim()) { ElMessage.warning(t('evalPage.questionRequired')); return }
  await http.post(`/eval/datasets/${currentDs.id}/items`, { ...itemForm })
  items.value = await http.get(`/eval/datasets/${currentDs.id}/items`) as any
  Object.assign(itemForm, { question: '', expectedAnswer: '', scoreCriteria: '' })
}

async function importItems() {
  if (!jsonl.value.trim()) return
  const res = await http.post(`/eval/datasets/${currentDs.id}/items/import`, { jsonl: jsonl.value }) as any
  ElMessage.success(`${t('evalPage.imported')}${res.imported}${t('evalPage.importedSuffix')}`)
  jsonl.value = ''
  items.value = await http.get(`/eval/datasets/${currentDs.id}/items`) as any
}

async function removeItem(row: any) {
  await http.delete(`/eval/datasets/items/${row.id}`)
  items.value = await http.get(`/eval/datasets/${currentDs.id}/items`) as any
}

function agentName(id: number | null) {
  return agents.value.find(a => a.id === id)?.name || '-'
}
function statusText(s: string) {
  return s === 'DONE' ? t('evalPage.done') : s === 'RUNNING' ? t('evalPage.running') : s === 'FAILED' ? t('evalPage.failedStatus') : t('evalPage.pending')
}
function fmt(t?: string) { return t ? String(t).replace('T', ' ').slice(5, 16) : '-' }
function clip(t: string, n: number) { t = t || ''; return t.length > n ? t.slice(0, n) + '…' : t }
</script>

<style scoped>
.page { max-width: 1150px; margin: 0 auto; }
.toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; }
.page-title { font-size: 16px; font-weight: 600; flex: 1; }
.hint-inline { font-size: 12px; color: #909399; max-width: 560px; }
.mb12 { margin-bottom: 12px; }
.sub-bar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.dialog-hint { font-size: 12px; color: #909399; margin-top: 6px; }
.sum-cards { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin-bottom: 14px; }
.sc { background: #f5f7fa; border-radius: 6px; padding: 10px; }
.k { font-size: 12px; color: #909399; }
.v { font-size: 20px; font-weight: 700; margin-top: 4px; }
.v.small { font-size: 14px; }
.expand { padding: 6px 12px; font-size: 13px; line-height: 1.7; }
.mt4 { margin-top: 4px; }
.mt8 { margin-top: 8px; }
.trace-link { color: #409eff; font-family: monospace; font-size: 12px; }
.import-box textarea { width: 100%; }
.item-form { display: flex; flex-direction: column; gap: 8px; }
</style>