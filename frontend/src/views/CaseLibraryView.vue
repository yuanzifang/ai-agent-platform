<template>
  <div class="page">
    <div class="toolbar">
      <span class="title">{{ t('page.cases') }}</span>
      <span class="sub">{{ t('casePage.sub') }}</span>
      <div class="spacer" />
      <el-select v-model="status" style="width: 130px" @change="load">
        <el-option :label="t('casePage.pending')" value="CANDIDATE" />
        <el-option :label="t('casePage.approved')" value="APPROVED" />
        <el-option :label="t('casePage.archived')" value="ARCHIVED" />
        <el-option :label="t('casePage.all')" value="" />
      </el-select>
      <el-select v-model="tier" clearable :placeholder="t('casePage.tier')" style="width: 140px" @change="load">
        <el-option :label="t('casePage.candidate')" value="CANDIDATE" />
        <el-option :label="t('casePage.approvedTier')" value="APPROVED" />
        <el-option :label="t('casePage.golden')" value="GOLDEN" />
      </el-select>
      <el-select v-model="kind" clearable :placeholder="t('casePage.kind')" style="width: 110px" @change="load">
        <el-option :label="t('casePage.classic')" value="CLASSIC" />
        <el-option :label="t('casePage.bad')" value="BAD" />
      </el-select>
      <el-input v-model="keyword" clearable :placeholder="t('casePage.searchPh')" style="width: 200px"
                @keyup.enter="load" @clear="load" />
      <el-button @click="load">{{ t('casePage.refresh') }}</el-button>
      <el-button :disabled="!approved.length" @click="exportCases">{{ t('casePage.export') }}</el-button>
      <el-button @click="openCalibration">{{ t('casePage.calibration') }}</el-button>
    </div>

    <div class="run-bar">
      <span class="hint">{{ t('casePage.regression') }}</span>
      <el-select v-model="modelName" filterable :placeholder="t('casePage.candidateModel')" style="width: 220px">
        <el-option v-for="m in chatModels" :key="m.modelName" :value="m.modelName"
                   :label="m.displayName || m.modelName" />
      </el-select>
      <el-select v-model="runTier" style="width: 130px">
        <el-option :label="t('casePage.approvedSet')" value="APPROVED" />
        <el-option :label="t('casePage.golden')" value="GOLDEN" />
      </el-select>
      <el-select v-model="judgeModel" filterable :placeholder="t('casePage.judgeModel')" style="width: 220px">
        <el-option v-for="m in chatModels" :key="'j' + m.modelName" :value="m.modelName"
                   :label="m.displayName || m.modelName" />
      </el-select>
      <el-button type="primary" :loading="running" :disabled="!runnable.length || !modelName"
                 @click="runRegression">{{ runTier === 'GOLDEN' ? t('casePage.runGolden') : t('casePage.runApproved') }}（{{ approved.length }}）</el-button>
      <span class="hint">{{ t('casePage.runHint') }}</span>
    </div>

    <el-empty v-if="!loading && rows.length === 0" :description="t('casePage.empty')" />
    <div v-else v-loading="loading" class="case-list">
      <div v-for="c in rows" :key="c.id" class="case-card">
        <div class="case-head">
          <el-tag size="small" :type="c.kind === 'CLASSIC' ? 'success' : 'danger'">
            {{ c.kind === 'CLASSIC' ? t('casePage.classicKind') : t('casePage.badKind') }}
          </el-tag>
          <el-tag size="small" effect="plain">{{ sourceText(c.source) }}</el-tag>
          <el-tag size="small" :type="c.status === 'APPROVED' ? 'success' : c.status === 'ARCHIVED' ? 'info' : 'warning'">
            {{ statusText(c.status) }}
          </el-tag>
          <el-tag v-if="c.datasetTier === 'GOLDEN'" size="small" type="warning">GOLDEN</el-tag>
          <span class="case-id">#{{ c.id }}</span>
          <div class="spacer" />
          <el-button size="small" text @click="openReview(c)">{{ t('casePage.review') }}</el-button>
          <el-button size="small" text type="danger" @click="remove(c)">{{ t('casePage.delete') }}</el-button>
        </div>
        <div class="case-q"><b>{{ t('casePage.qPrefix') }}</b>{{ c.question }}</div>
        <div class="case-a"><b>{{ t('casePage.original') }}</b>{{ c.actualAnswer }}</div>
        <div v-if="c.expectedAnswer" class="case-expected"><b>{{ t('casePage.expected') }}</b>{{ c.expectedAnswer }}</div>
        <div v-if="c.note" class="case-note">{{ t('casePage.notePrefix') }}{{ c.note }}</div>
      </div>
    </div>

    <el-dialog v-model="reviewVisible" :title="t('casePage.reviewTitle')" width="620px">
      <el-form label-width="90px">
        <el-form-item :label="t('casePage.question')">
          <el-input v-model="form.question" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item :label="t('casePage.originalAnswer')">
          <el-input v-model="form.actualAnswer" type="textarea" :rows="3" readonly />
        </el-form-item>
        <el-form-item :label="t('casePage.type')">
          <el-radio-group v-model="form.kind">
            <el-radio value="CLASSIC">{{ t('casePage.classicOption') }}</el-radio>
            <el-radio value="BAD">{{ t('casePage.badOption') }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="t('casePage.expectedLabel')" :required="form.kind === 'BAD'">
          <el-input v-model="form.expectedAnswer" type="textarea" :rows="3"
                    :placeholder="t('casePage.expectedPh')" />
        </el-form-item>
        <el-form-item :label="t('casePage.reviewStatus')">
          <el-select v-model="form.status" style="width: 160px">
            <el-option :label="t('casePage.pending')" value="CANDIDATE" />
            <el-option :label="t('casePage.statusApproved')" value="APPROVED" />
            <el-option :label="t('casePage.statusArchived')" value="ARCHIVED" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('casePage.tierLabel')">
          <el-select v-model="form.datasetTier" style="width: 180px">
            <el-option :label="t('casePage.candidate')" value="CANDIDATE" />
            <el-option :label="t('casePage.approvedTier')" value="APPROVED" />
            <el-option :label="t('casePage.goldenOption')" value="GOLDEN" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('casePage.risk')">
          <el-select v-model="form.riskLevel" clearable style="width: 160px">
            <el-option label="LOW" value="LOW" />
            <el-option label="MEDIUM" value="MEDIUM" />
            <el-option label="HIGH" value="HIGH" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('casePage.coverage')">
          <el-input v-model="form.coverageTagsText" :placeholder="t('casePage.coveragePh')" />
        </el-form-item>
        <el-form-item :label="t('casePage.expiry')">
          <el-date-picker v-model="form.expiresAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss"
                          :placeholder="t('casePage.expiryPh')" style="width: 220px" />
        </el-form-item>
        <el-form-item :label="t('casePage.formNote')">
          <el-input v-model="form.note" maxlength="512" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reviewVisible = false">{{ t('casePage.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" @click="saveReview">{{ t('casePage.save') }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="resultVisible" :title="t('casePage.resultTitle')" width="900px">
      <div v-if="currentRun" class="run-summary">
        {{ t('casePage.resultModel') }}{{ currentRun.modelName }}{{ t('casePage.resultPassed') }}{{ currentRun.passed }}/{{ currentRun.total }}{{ t('casePage.resultAvg') }}
        {{ currentRun.avgScore ?? '-' }}{{ t('casePage.resultJudge') }}{{ currentRun.judgeModel }}
      </div>
      <el-table :data="currentRun?.items || []" size="small" max-height="420">
        <el-table-column prop="question" :label="t('casePage.question')" min-width="200" show-overflow-tooltip />
        <el-table-column prop="answer" :label="t('casePage.answer')" min-width="260" show-overflow-tooltip />
        <el-table-column prop="score" :label="t('casePage.score')" width="80" />
        <el-table-column :label="t('casePage.result')" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.passed ? 'success' : 'danger'">{{ row.passed ? t('casePage.pass') : t('casePage.fail') }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="error" :label="t('casePage.error')" min-width="120" show-overflow-tooltip />
      </el-table>
    </el-dialog>

    <el-drawer v-model="calVisible" :title="t('casePage.calTitle')" size="1100px">
      <div class="cal-stats">
        <el-statistic :title="t('casePage.statDetails')" :value="calStats.total || 0" />
        <el-statistic :title="t('casePage.statLabeled')" :value="calStats.labeled || 0" />
        <el-statistic :title="t('casePage.statPending')" :value="calStats.pending || 0" />
        <el-statistic :title="t('casePage.statAgreement')" :value="(calStats.agreementRate || 0) * 100">
          <template #suffix>%</template>
        </el-statistic>
        <el-statistic :title="t('casePage.statMeanError')" :value="calStats.meanAbsError || 0" />
        <el-statistic :title="t('casePage.statInter')" :value="(calStats.interAnnotatorAgreement || 0) * 100">
          <template #suffix>%</template>
        </el-statistic>
        <el-statistic :title="t('casePage.statSampled')" :value="calStats.sampled || 0" />
        <el-statistic :title="t('casePage.statAssigned')" :value="calStats.assigned || 0" />
      </div>
      <div class="toolbar">
        <el-radio-group v-model="calStatus" @change="loadCalibration">
          <el-radio-button value="PENDING">{{ t('casePage.calPending') }}</el-radio-button>
          <el-radio-button value="DONE">{{ t('casePage.calDone') }}</el-radio-button>
          <el-radio-button value="ALL">{{ t('casePage.calAll') }}</el-radio-button>
        </el-radio-group>
        <el-button @click="loadCalibration">{{ t('casePage.refresh') }}</el-button>
        <el-button @click="sampleCalibration">{{ t('casePage.sample') }}</el-button>
        <el-checkbox v-model="calBlind" @change="loadCalibration">{{ t('casePage.blind') }}</el-checkbox>
        <el-checkbox v-model="calMine" @change="loadCalibration">{{ t('casePage.mine') }}</el-checkbox>
        <el-checkbox v-model="calSampled" @change="loadCalibration">{{ t('casePage.sampledOnly') }}</el-checkbox>
        <span class="hint">{{ t('casePage.calHint') }}</span>
      </div>
      <el-table :data="calRows" size="small" v-loading="calLoading" max-height="560">
        <el-table-column prop="runName" :label="t('casePage.runTask')" width="150" show-overflow-tooltip />
        <el-table-column prop="modelName" :label="t('casePage.candidateModelCol')" width="120" show-overflow-tooltip />
        <el-table-column prop="question" :label="t('casePage.question')" min-width="180" show-overflow-tooltip />
        <el-table-column prop="answer" :label="t('casePage.answer')" min-width="220" show-overflow-tooltip />
        <el-table-column prop="referenceAnswer" :label="t('casePage.reference')" min-width="180" show-overflow-tooltip />
        <el-table-column prop="labelCount" :label="t('casePage.labelCount')" width="75" />
        <el-table-column :label="t('casePage.sampleAssign')" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.sampled" type="warning" size="small">{{ t('casePage.sampledTag') }}</el-tag>
            <el-tag v-if="row.assigneeId" type="success" size="small">{{ t('casePage.assignedTag') }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('casePage.judge')" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.judgePassed ? 'success' : 'danger'">
              {{ row.judgeScore ?? '-' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('casePage.humanScore')" width="110">
          <template #default="{ row }">
            <el-input-number v-model="row.humanScore" :min="0" :max="1" :step="0.1" size="small" />
          </template>
        </el-table-column>
        <el-table-column :label="t('casePage.humanLabel')" width="110">
          <template #default="{ row }">
            <el-select v-model="row.humanLabel" size="small" :placeholder="t('casePage.selectLabel')">
              <el-option label="PASS" value="PASS" />
              <el-option label="FAIL" value="FAIL" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column :label="t('casePage.noteCol')" width="140">
          <template #default="{ row }"><el-input v-model="row.reviewNote" size="small" /></template>
        </el-table-column>
        <el-table-column :label="t('casePage.actions')" width="170" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text @click="assignToMe(row)">{{ t('casePage.assignMe') }}</el-button>
            <el-button size="small" type="primary" text @click="saveCalibration(row)">{{ t('casePage.save') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="cal-pager">
        <el-pagination v-model:current-page="calPage" :page-size="20" :total="calTotal"
                       layout="prev, pager, next, total" @current-change="loadCalibration" />
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'

const { t } = useI18n()

const rows = ref<any[]>([])
const approvedRows = ref<any[]>([])
const chatModels = ref<any[]>([])
const loading = ref(false)
const saving = ref(false)
const running = ref(false)
const status = ref('CANDIDATE')
const route = useRoute()
const filterAgentId = ref<number | null>(route.query.agentId ? Number(route.query.agentId) : null)
const kind = ref('')
const tier = ref('')
const runTier = ref('APPROVED')
const keyword = ref('')
const reviewVisible = ref(false)
const resultVisible = ref(false)
const currentRun = ref<any>(null)
const modelName = ref('')
const judgeModel = ref('')
const form = reactive({ id: 0, question: '', actualAnswer: '', expectedAnswer: '', kind: 'BAD',
  status: 'CANDIDATE', datasetTier: 'CANDIDATE', riskLevel: '', coverageTagsText: '', expiresAt: '', note: '' })
const approved = computed(() => approvedRows.value)
const runnable = computed(() => approved.value.filter(c => runTier.value !== 'GOLDEN' || c.datasetTier === 'GOLDEN'))
const calVisible = ref(false)
const calLoading = ref(false)
const calStatus = ref('PENDING')
const calBlind = ref(false)
const calMine = ref(false)
const calSampled = ref(false)
const calRows = ref<any[]>([])
const calStats = ref<any>({})
const calPage = ref(1)
const calTotal = ref(0)

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
          keyword: keyword.value || undefined, tier: tier.value || undefined,
          agentId: filterAgentId.value || undefined }
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
    expectedAnswer: c.expectedAnswer || '', kind: c.kind, status: c.status,
    datasetTier: c.datasetTier || (c.status === 'APPROVED' ? 'APPROVED' : 'CANDIDATE'),
    riskLevel: c.riskLevel || '',
    coverageTagsText: Array.isArray(c.coverageTags) ? c.coverageTags.join(',') : (c.coverageTags || ''),
    expiresAt: c.expiresAt || '', note: c.note || ''
  })
  reviewVisible.value = true
}

async function saveReview() {
  if (form.kind === 'BAD' && form.status === 'APPROVED' && !form.expectedAnswer.trim()) {
    ElMessage.warning(t('casePage.badExpectedRequired'))
    return
  }
  saving.value = true
  try {
    const coverageTags = form.coverageTagsText.split(',').map(v => v.trim()).filter(Boolean)
    await http.put(`/cases/${form.id}`, {
      expectedAnswer: form.expectedAnswer, kind: form.kind, status: form.status,
      datasetTier: form.datasetTier, riskLevel: form.riskLevel,
      coverageTags, expiresAt: form.expiresAt || null, note: form.note
    })
    ElMessage.success(t('casePage.saved'))
    reviewVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function remove(c: any) {
  try {
    await ElMessageBox.confirm(`${t('casePage.deletePrefix')}${c.id}${t('casePage.deleteSuffix')}`, t('casePage.deleteTitle'), { type: 'warning' })
  } catch { return }
  await http.delete(`/cases/${c.id}`)
  ElMessage.success(t('casePage.deleted'))
  await load()
}

async function exportCases() {
  const res = await http.post('/cases/export', {
    name: `${t('casePage.exportName')}${new Date().toISOString().slice(0, 19)}`,
    caseIds: approved.value.map(c => c.id)
  }) as any
  ElMessage.success(`${t('casePage.exported')}${res.cases}${t('casePage.exportedMid')}${res.datasetId}`)
}

async function runRegression() {
  if (!modelName.value) { ElMessage.warning(t('casePage.selectModel')); return }
  running.value = true
  try {
    currentRun.value = await http.post('/cases/run', {
      modelName: modelName.value,
      judgeModel: judgeModel.value || modelName.value,
      tier: runTier.value,
      caseIds: approved.value.filter(c => runTier.value !== 'GOLDEN' || c.datasetTier === 'GOLDEN').map(c => c.id)
    }, { timeout: 300000 }) as any
    resultVisible.value = true
    ElMessage.success(`${t('casePage.regressionDone')}${currentRun.value.passed}/${currentRun.value.total}`)
  } finally {
    running.value = false
  }
}

async function openCalibration() {
  calVisible.value = true
  calPage.value = 1
  await loadCalibration()
}

async function loadCalibration() {
  calLoading.value = true
  try {
    const [data, stats] = await Promise.all([
      http.get('/cases/calibrations', {
        params: { status: calStatus.value, page: calPage.value, pageSize: 20, blind: calBlind.value,
          mine: calMine.value, sampledOnly: calSampled.value }
      }),
      http.get('/cases/calibrations/stats')
    ]) as any[]
    calRows.value = data.list || []
    calTotal.value = data.total || 0
    calStats.value = stats || {}
  } finally {
    calLoading.value = false
  }
}

async function sampleCalibration() {
  try {
    const res = await http.post('/cases/calibrations/sample', { percent: 20 }) as any
    ElMessage.success(`${t('casePage.sampledToast')}${res.sampled}${t('casePage.sampledSlash')}${res.total}`)
    await loadCalibration()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || t('casePage.sampleFail'))
  }
}

async function assignToMe(row: any) {
  try {
    await http.put(`/cases/calibrations/${row.id}/assign`, {})
    ElMessage.success(t('casePage.assignedToast'))
    await loadCalibration()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || t('casePage.assignFail'))
  }
}

async function saveCalibration(row: any) {
  if (!row.humanLabel) { ElMessage.warning(t('casePage.labelRequired')); return }
  try {
    await http.put(`/cases/calibrations/${row.id}`, {
      humanScore: row.humanScore, humanLabel: row.humanLabel, note: row.reviewNote
    })
    ElMessage.success(t('casePage.labelSaved'))
    await loadCalibration()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || t('casePage.saveFail'))
  }
}

function sourceText(source: string) {
  return ({ MANUAL: t('casePage.sourceManual'), AUTO_BAD: t('casePage.sourceAutoBad'), AUTO_CLASSIC: t('casePage.sourceAutoClassic') } as any)[source] || source
}
function statusText(value: string) {
  return ({ CANDIDATE: t('casePage.statusCandidate'), APPROVED: t('casePage.approved'), ARCHIVED: t('casePage.archived') } as any)[value] || value
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
.case-a, .case-expected, .cal-stats { display: flex; gap: 28px; padding: 8px 0 16px; }
.cal-pager { display: flex; justify-content: flex-end; margin-top: 12px; }
.case-note {
  margin-top: 4px; font-size: 13px; color: #606266; line-height: 1.5;
  white-space: pre-wrap; word-break: break-word;
}
.case-expected { color: #409eff; }
.run-summary { margin-bottom: 10px; font-size: 13px; color: #606266; }
</style>