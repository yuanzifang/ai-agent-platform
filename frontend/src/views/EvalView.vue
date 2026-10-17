<template>
  <div class="page">
    <div class="toolbar">
      <el-button text @click="$router.push('/agents')">
        <el-icon><ArrowLeft /></el-icon> 返回
      </el-button>
      <span class="page-title">Agent 结果评测</span>
      <el-radio-group v-model="tab">
        <el-radio-button value="once">一次性评测</el-radio-button>
        <el-radio-button value="online">在线监控</el-radio-button>
        <el-radio-button value="pre">预发布评测</el-radio-button>
        <el-radio-button value="dataset">数据集管理</el-radio-button>
      </el-radio-group>
    </div>

    <!-- 一次性评测 -->
    <template v-if="tab === 'once'">
      <div class="sub-bar">
        <span></span>
        <div>
          <el-select v-model="agentId" placeholder="选择 Agent" size="small" style="width: 180px; margin-right: 8px"
                     @change="loadTasks">
            <el-option v-for="a in agents" :key="a.id" :value="a.id" :label="a.name" />
          </el-select>
          <el-button type="primary" size="small" @click="taskDialog = true">＋ 开始评测</el-button>
        </div>
      </div>
      <el-card shadow="never">
        <el-table :data="tasks" size="small" @row-click="openTask">
          <el-table-column prop="name" label="任务名" min-width="150" />
          <el-table-column prop="datasetName" label="数据集" width="130" />
          <el-table-column label="进度" width="90">
            <template #default="{ row }">{{ row.completedCalls ?? 0 }}/{{ row.estimatedCalls ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'DONE' ? 'success' : row.status === 'RUNNING' ? 'warning' : row.status === 'FAILED' ? 'danger' : 'info'">
                {{ statusText(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="平均分" width="90">
            <template #default="{ row }">{{ row.avgScore != null ? (row.avgScore * 100).toFixed(1) : '-' }}</template>
          </el-table-column>
          <el-table-column label="通过率" width="90">
            <template #default="{ row }">{{ row.passRate != null ? (row.passRate * 100).toFixed(0) + '%' : '-' }}</template>
          </el-table-column>
          <el-table-column label="时间" width="140">
            <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
          </el-table-column>
        </el-table>
        <el-empty v-if="tasks.length === 0" description="暂无评测任务，点击「开始评测」创建" :image-size="60" />
      </el-card>
    </template>

    <!-- 在线监控 / 预发布（P1 后续批次） -->
    <el-card v-else-if="tab === 'online' || tab === 'pre'" shadow="never">
      <el-empty :description="tab === 'online'
        ? '在线监控：基于线上 Trace 抽样评测 + 点踩触发评测（eval_type=ONLINE/FEEDBACK），随 P1 后续批次交付'
        : '预发布评测：发布前自动跑数据集，得分低于门禁阈值(sys_config publish.gate-threshold)阻断发布，随 P1 后续批次交付'" />
    </el-card>

    <!-- 数据集管理 -->
    <template v-else>
      <div class="sub-bar">
        <span></span>
        <el-button type="primary" size="small" @click="dsDialog = true">＋ 新建数据集</el-button>
      </div>
      <el-card shadow="never">
        <el-table :data="datasets" size="small">
          <el-table-column prop="name" label="名称" width="180" />
          <el-table-column prop="description" label="描述" min-width="180" />
          <el-table-column prop="itemCount" label="样本数" width="80" />
          <el-table-column label="绑定 Agent" width="110">
            <template #default="{ row }">{{ agentName(row.agentId) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="180">
            <template #default="{ row }">
              <el-button size="small" @click="openItems(row)">样本管理</el-button>
              <el-button size="small" type="danger" plain @click="removeDs(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </template>

    <!-- 开始评测弹窗 -->
    <el-dialog v-model="taskDialog" title="开始评测" width="480px">
      <el-form label-width="90px">
        <el-form-item label="任务名">
          <el-input v-model="taskForm.name" placeholder="留空自动生成" />
        </el-form-item>
        <el-form-item label="Agent" required>
          <el-select v-model="taskForm.agentId" style="width: 100%">
            <el-option v-for="a in agents" :key="a.id" :value="a.id" :label="a.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="数据集" required>
          <el-select v-model="taskForm.datasetId" style="width: 100%">
            <el-option v-for="d in datasets" :key="d.id" :value="d.id" :label="`${d.name}（${d.itemCount} 样本）`" />
          </el-select>
        </el-form-item>
        <el-form-item label="裁判模型">
          <el-select v-model="taskForm.judgeModel" style="width: 100%">
            <el-option v-for="m in models" :key="m.modelName" :value="m.modelName" :label="m.displayName || m.modelName" />
          </el-select>
        </el-form-item>
        <el-form-item label="通过阈值">
          <el-slider v-model="taskForm.passThreshold" :min="0.3" :max="0.95" :step="0.05" style="width: 90%" />
        </el-form-item>
      </el-form>
      <div class="dialog-hint">流程：遍历样本 → 调 Agent（含知识库 RAG）→ 生成 Trace → LLM 裁判打分 → 汇总平均分与通过率</div>
      <template #footer>
        <el-button @click="taskDialog = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="createTask">提交并执行</el-button>
      </template>
    </el-dialog>

    <!-- 任务详情抽屉 -->
    <el-drawer v-model="taskVisible" :title="'评测结果 · ' + (current.name || '')" size="70%">
      <div class="sum-cards">
        <div class="sc"><div class="k">状态</div><div class="v">{{ statusText(current.status) }}</div></div>
        <div class="sc"><div class="k">平均分</div><div class="v">{{ current.avgScore != null ? (current.avgScore * 100).toFixed(1) : '-' }}</div></div>
        <div class="sc"><div class="k">通过率</div><div class="v">{{ current.passRate != null ? (current.passRate * 100).toFixed(0) + '%' : '-' }}</div></div>
        <div class="sc"><div class="k">裁判模型</div><div class="v small">{{ current.judgeModel }}</div></div>
      </div>
      <el-table :data="current.results || []" size="small">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="expand">
              <div><b>期望答案：</b>{{ row.expectedAnswer || '（无）' }}</div>
              <div class="mt4"><b>实际回答：</b>{{ row.answer }}</div>
              <div class="mt4"><b>裁判理由：</b>{{ row.judgeReason }}</div>
              <div class="mt4 trace-link">Trace: {{ row.traceId }}</div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="问题" min-width="140">
          <template #default="{ row }">{{ clip(row.question, 24) }}</template>
        </el-table-column>
        <el-table-column label="回答" min-width="180">
          <template #default="{ row }">{{ clip(row.answer, 32) }}</template>
        </el-table-column>
        <el-table-column label="评分" width="80">
          <template #default="{ row }">{{ row.score != null ? (row.score * 100).toFixed(0) : '-' }}</template>
        </el-table-column>
        <el-table-column label="通过" width="70">
          <template #default="{ row }">
            <el-tag size="small" :type="row.passed ? 'success' : 'danger'">{{ row.passed ? '通过' : '未过' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="延迟" width="80">
          <template #default="{ row }">{{ row.latencyMs }}ms</template>
        </el-table-column>
        <el-table-column prop="tokens" label="Token" width="80" />
      </el-table>
    </el-drawer>

    <!-- 样本管理抽屉 -->
    <el-drawer v-model="itemsVisible" :title="'样本管理 · ' + (currentDs.name || '')" size="640px">
      <div class="import-box">
        <el-input v-model="jsonl" type="textarea" :rows="3"
                  placeholder='批量导入 JSONL：{"question":"...","expectedAnswer":"...","scoreCriteria":"..."}' />
        <el-button size="small" class="mt8" @click="importItems">导入</el-button>
      </div>
      <el-divider />
      <div class="item-form">
        <el-input v-model="itemForm.question" placeholder="问题" size="small" />
        <el-input v-model="itemForm.expectedAnswer" placeholder="期望答案" size="small" />
        <el-input v-model="itemForm.scoreCriteria" placeholder="评分标准（可空，默认与期望一致/完整/不编造）" size="small" />
        <el-button size="small" type="primary" @click="addItem">添加样本</el-button>
      </div>
      <el-table :data="items" size="small" class="mt8">
        <el-table-column label="问题" min-width="160">
          <template #default="{ row }">{{ clip(row.question, 30) }}</template>
        </el-table-column>
        <el-table-column label="期望答案" min-width="160">
          <template #default="{ row }">{{ clip(row.expectedAnswer, 30) }}</template>
        </el-table-column>
        <el-table-column width="60">
          <template #default="{ row }">
            <el-button size="small" text type="danger" @click="removeItem(row)">删</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <!-- 新建数据集 -->
    <el-dialog v-model="dsDialog" title="新建数据集" width="460px">
      <el-form label-width="80px">
        <el-form-item label="名称" required><el-input v-model="dsForm.name" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="dsForm.description" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="绑定Agent">
          <el-select v-model="dsForm.agentId" clearable placeholder="可选，作为默认数据集" style="width: 100%">
            <el-option v-for="a in agents" :key="a.id" :value="a.id" :label="a.name" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dsDialog = false">取消</el-button>
        <el-button type="primary" @click="createDs">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import http from '../api/http'

const route = useRoute()
const tab = ref('once')
const agentId = ref<number | null>(route.params.agentId ? Number(route.params.agentId) : null)
const agents = ref<any[]>([])
const models = ref<any[]>([])
const datasets = ref<any[]>([])
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
  tasks.value = data.list || []
}

async function createTask() {
  if (!taskForm.agentId || !taskForm.datasetId) { ElMessage.warning('请选择 Agent 与数据集'); return }
  creating.value = true
  try {
    await http.post('/eval/tasks', { ...taskForm })
    ElMessage.success('任务已提交，后台执行中（可稍后刷新看进度）')
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
  if (!dsForm.name.trim()) { ElMessage.warning('名称必填'); return }
  await http.post('/eval/datasets', { ...dsForm })
  ElMessage.success('已创建')
  dsDialog.value = false
  datasets.value = await http.get('/eval/datasets') as any
}

async function removeDs(row: any) {
  await ElMessageBox.confirm(`删除数据集「${row.name}」？被任务引用时将拒绝删除`, '确认', { type: 'warning' })
  await http.delete(`/eval/datasets/${row.id}`)
  datasets.value = await http.get('/eval/datasets') as any
}

async function openItems(row: any) {
  Object.assign(currentDs, row)
  itemsVisible.value = true
  items.value = await http.get(`/eval/datasets/${row.id}/items`) as any
}

async function addItem() {
  if (!itemForm.question.trim()) { ElMessage.warning('问题必填'); return }
  await http.post(`/eval/datasets/${currentDs.id}/items`, { ...itemForm })
  items.value = await http.get(`/eval/datasets/${currentDs.id}/items`) as any
  Object.assign(itemForm, { question: '', expectedAnswer: '', scoreCriteria: '' })
}

async function importItems() {
  if (!jsonl.value.trim()) return
  const res = await http.post(`/eval/datasets/${currentDs.id}/items/import`, { jsonl: jsonl.value }) as any
  ElMessage.success(`已导入 ${res.imported} 条`)
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
  return s === 'DONE' ? '已完成' : s === 'RUNNING' ? '执行中' : s === 'FAILED' ? '失败' : '待执行'
}
function fmt(t?: string) { return t ? String(t).replace('T', ' ').slice(5, 16) : '-' }
function clip(t: string, n: number) { t = t || ''; return t.length > n ? t.slice(0, n) + '…' : t }
</script>

<style scoped>
.page { max-width: 1150px; margin: 0 auto; }
.toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; }
.page-title { font-size: 16px; font-weight: 600; flex: 1; }
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
