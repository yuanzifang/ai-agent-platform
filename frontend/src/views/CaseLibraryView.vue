<template>
  <div class="page">
    <div class="toolbar">
      <span class="title">对话用例库</span>
      <span class="sub">点赞/点踩自动沉淀候选；人工审核并修正期望答案后才能用于模型回归</span>
      <div class="spacer" />
      <el-select v-model="status" style="width: 130px" @change="load">
        <el-option label="待审核" value="CANDIDATE" />
        <el-option label="已通过" value="APPROVED" />
        <el-option label="已归档" value="ARCHIVED" />
        <el-option label="全部" value="" />
      </el-select>
      <el-select v-model="kind" clearable placeholder="类型" style="width: 110px" @change="load">
        <el-option label="经典" value="CLASSIC" />
        <el-option label="坏例" value="BAD" />
      </el-select>
      <el-input v-model="keyword" clearable placeholder="搜索问题" style="width: 200px"
                @keyup.enter="load" @clear="load" />
      <el-button @click="load">刷新</el-button>
      <el-button :disabled="!approved.length" @click="exportCases">导出评测集</el-button>
    </div>

    <div class="run-bar">
      <span class="hint">模型回归：</span>
      <el-select v-model="modelName" filterable placeholder="候选模型" style="width: 220px">
        <el-option v-for="m in chatModels" :key="m.modelName" :value="m.modelName"
                   :label="m.displayName || m.modelName" />
      </el-select>
      <el-select v-model="judgeModel" filterable placeholder="裁判模型" style="width: 220px">
        <el-option v-for="m in chatModels" :key="'j' + m.modelName" :value="m.modelName"
                   :label="m.displayName || m.modelName" />
      </el-select>
      <el-button type="primary" :loading="running" :disabled="!approved.length || !modelName"
                 @click="runRegression">运行已审核用例（{{ approved.length }}）</el-button>
      <span class="hint">classic 与 bad 同批执行；坏例必须已填写期望答案</span>
    </div>

    <el-empty v-if="!loading && rows.length === 0" description="暂无用例：在对话中点赞/点踩，或手动收藏 AI 回复" />
    <div v-else v-loading="loading" class="case-list">
      <div v-for="c in rows" :key="c.id" class="case-card">
        <div class="case-head">
          <el-tag size="small" :type="c.kind === 'CLASSIC' ? 'success' : 'danger'">
            {{ c.kind === 'CLASSIC' ? '经典' : '坏例' }}
          </el-tag>
          <el-tag size="small" effect="plain">{{ sourceText(c.source) }}</el-tag>
          <el-tag size="small" :type="c.status === 'APPROVED' ? 'success' : c.status === 'ARCHIVED' ? 'info' : 'warning'">
            {{ statusText(c.status) }}
          </el-tag>
          <span class="case-id">#{{ c.id }}</span>
          <div class="spacer" />
          <el-button size="small" text @click="openReview(c)">审核 / 修正</el-button>
          <el-button size="small" text type="danger" @click="remove(c)">删除</el-button>
        </div>
        <div class="case-q"><b>问：</b>{{ c.question }}</div>
        <div class="case-a"><b>原回答：</b>{{ c.actualAnswer }}</div>
        <div v-if="c.expectedAnswer" class="case-expected"><b>期望答案：</b>{{ c.expectedAnswer }}</div>
        <div v-if="c.note" class="case-note">备注：{{ c.note }}</div>
      </div>
    </div>

    <el-dialog v-model="reviewVisible" title="审核 / 修正用例" width="620px">
      <el-form label-width="90px">
        <el-form-item label="问题">
          <el-input v-model="form.question" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="原回答">
          <el-input v-model="form.actualAnswer" type="textarea" :rows="3" readonly />
        </el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="form.kind">
            <el-radio value="CLASSIC">经典（原回答作为参考）</el-radio>
            <el-radio value="BAD">坏例（需修正期望答案）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="期望答案" :required="form.kind === 'BAD'">
          <el-input v-model="form.expectedAnswer" type="textarea" :rows="3"
                    placeholder="坏例审核通过前必须填写正确的期望答案；经典可留空沿用原回答" />
        </el-form-item>
        <el-form-item label="审核状态">
          <el-select v-model="form.status" style="width: 160px">
            <el-option label="待审核" value="CANDIDATE" />
            <el-option label="通过（可用于回归）" value="APPROVED" />
            <el-option label="归档" value="ARCHIVED" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.note" maxlength="512" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reviewVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveReview">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="resultVisible" title="回归结果" width="900px">
      <div v-if="currentRun" class="run-summary">
        模型 {{ currentRun.modelName }} · 通过 {{ currentRun.passed }}/{{ currentRun.total }} ·
        平均分 {{ currentRun.avgScore ?? '-' }} · 裁判 {{ currentRun.judgeModel }}
      </div>
      <el-table :data="currentRun?.items || []" size="small" max-height="420">
        <el-table-column prop="question" label="问题" min-width="200" show-overflow-tooltip />
        <el-table-column prop="answer" label="候选回答" min-width="260" show-overflow-tooltip />
        <el-table-column prop="score" label="分数" width="80" />
        <el-table-column label="结果" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.passed ? 'success' : 'danger'">{{ row.passed ? '通过' : '失败' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="error" label="错误" min-width="120" show-overflow-tooltip />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'

const rows = ref<any[]>([])
const approvedRows = ref<any[]>([])
const chatModels = ref<any[]>([])
const loading = ref(false)
const saving = ref(false)
const running = ref(false)
const status = ref('CANDIDATE')
const kind = ref('')
const keyword = ref('')
const reviewVisible = ref(false)
const resultVisible = ref(false)
const currentRun = ref<any>(null)
const modelName = ref('')
const judgeModel = ref('')
const form = reactive({ id: 0, question: '', actualAnswer: '', expectedAnswer: '', kind: 'BAD',
  status: 'CANDIDATE', note: '' })
const approved = computed(() => approvedRows.value)

onMounted(async () => {
  const models = await http.get('/provider/models', { params: { purpose: 'CHAT' } }) as any
  chatModels.value = (models || []).filter((m: any) => m.status !== 'DISABLED')
  modelName.value = chatModels.value[0]?.modelName || ''
  judgeModel.value = modelName.value
  await load()
})

async function load() {
  loading.value = true
  try {
    const [data, approvedData] = await Promise.all([
      http.get('/cases', {
        params: { status: status.value || undefined, kind: kind.value || undefined,
          keyword: keyword.value || undefined }
      }),
      http.get('/cases', { params: { status: 'APPROVED' } })
    ]) as any[]
    rows.value = data.list || []
    approvedRows.value = approvedData.list || []
  } finally {
    loading.value = false
  }
}

function openReview(c: any) {
  Object.assign(form, {
    id: c.id, question: c.question, actualAnswer: c.actualAnswer || '',
    expectedAnswer: c.expectedAnswer || '', kind: c.kind, status: c.status, note: c.note || ''
  })
  reviewVisible.value = true
}

async function saveReview() {
  if (form.kind === 'BAD' && form.status === 'APPROVED' && !form.expectedAnswer.trim()) {
    ElMessage.warning('坏例审核通过前必须填写期望答案')
    return
  }
  saving.value = true
  try {
    await http.put(`/cases/${form.id}`, {
      expectedAnswer: form.expectedAnswer, kind: form.kind, status: form.status, note: form.note
    })
    ElMessage.success('已保存')
    reviewVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function remove(c: any) {
  try {
    await ElMessageBox.confirm(`删除用例 #${c.id}？`, '删除用例', { type: 'warning' })
  } catch { return }
  await http.delete(`/cases/${c.id}`)
  ElMessage.success('已删除')
  await load()
}

async function exportCases() {
  const res = await http.post('/cases/export', {
    name: `用例库导出-${new Date().toISOString().slice(0, 19)}`,
    caseIds: approved.value.map(c => c.id)
  }) as any
  ElMessage.success(`已导出 ${res.cases} 条到评测数据集 #${res.datasetId}`)
}

async function runRegression() {
  if (!modelName.value) { ElMessage.warning('请选择候选模型'); return }
  running.value = true
  try {
    currentRun.value = await http.post('/cases/run', {
      modelName: modelName.value,
      judgeModel: judgeModel.value || modelName.value,
      caseIds: approved.value.map(c => c.id)
    }, { timeout: 300000 }) as any
    resultVisible.value = true
    ElMessage.success(`回归完成：通过 ${currentRun.value.passed}/${currentRun.value.total}`)
  } finally {
    running.value = false
  }
}

function sourceText(source: string) {
  return ({ MANUAL: '手动收藏', AUTO_BAD: '点踩自动', AUTO_CLASSIC: '点赞自动' } as any)[source] || source
}
function statusText(value: string) {
  return ({ CANDIDATE: '待审核', APPROVED: '已通过', ARCHIVED: '已归档' } as any)[value] || value
}
</script>

<style scoped>
.page { background: #fff; border: 1px solid #e4e7ed; border-radius: 6px; padding: 14px; }
.toolbar, .run-bar { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
.title { font-weight: 600; font-size: 15px; }
.sub, .hint { color: #909399; font-size: 12px; }
.spacer { flex: 1; }
.case-list { display: flex; flex-direction: column; gap: 10px; }
.case-card { border: 1px solid #ebeef5; border-radius: 6px; padding: 10px 12px; }
.case-head { display: flex; align-items: center; gap: 8px; }
.case-id { font-size: 12px; color: #c0c4cc; }
.case-q { margin-top: 8px; font-size: 14px; line-height: 1.5; }
.case-a, .case-expected, .case-note {
  margin-top: 4px; font-size: 13px; color: #606266; line-height: 1.5;
  white-space: pre-wrap; word-break: break-word;
}
.case-expected { color: #409eff; }
.run-summary { margin-bottom: 10px; font-size: 13px; color: #606266; }
</style>
