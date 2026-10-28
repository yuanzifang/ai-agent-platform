<template>
  <div class="page">
    <div class="toolbar">
      <span class="title">{{ t('page.approvals') }}</span>
      <span class="sub">{{ t('approval.sub') }}</span>
      <div class="spacer" />
      <el-select v-model="status" style="width: 140px" @change="load(true)">
        <el-option :label="t('approval.pending')" value="PENDING" />
        <el-option :label="t('approval.approved')" value="APPROVED" />
        <el-option :label="t('approval.rejected')" value="REJECTED" />
        <el-option :label="t('approval.executed')" value="EXECUTED" />
        <el-option :label="t('approval.failed')" value="FAILED" />
        <el-option :label="t('approval.expired')" value="EXPIRED" />
        <el-option :label="t('approval.all')" value="ALL" />
      </el-select>
      <el-button @click="load(true)">{{ t('approval.refresh') }}</el-button>
    </div>

    <el-table :data="list" size="small" v-loading="loading" :empty-text="t('approval.empty')">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="agentName" label="Agent" width="150" />
      <el-table-column prop="title" :label="t('approval.title')" width="180" />
      <el-table-column :label="t('approval.role')" width="100">
        <template #default="{ row }">{{ row.requiredRole || t('approval.anyRole') }}</template>
      </el-table-column>
      <el-table-column :label="t('approval.content')" min-width="260" show-overflow-tooltip>
        <template #default="{ row }">{{ row.payload || row.runInput || '-' }}</template>
      </el-table-column>
      <el-table-column :label="t('approval.status')" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="statusType(row.status)">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('approval.submitted')" width="170">
        <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column :label="t('approval.expireAt')" width="170">
        <template #default="{ row }">{{ fmt(row.expireAt) }}</template>
      </el-table-column>
      <el-table-column :label="t('approval.handler')" min-width="160">
        <template #default="{ row }">{{ row.comment || '-' }}</template>
      </el-table-column>
      <el-table-column :label="t('approval.actions')" width="150">
        <template #default="{ row }">
          <template v-if="row.status === 'PENDING'">
            <el-button size="small" type="primary" text @click="openDecide(row, true)">{{ t('approval.approve') }}</el-button>
            <el-button size="small" type="danger" text @click="openDecide(row, false)">{{ t('approval.reject') }}</el-button>
          </template>
          <el-button size="small" text @click="viewRun(row)">{{ t('approval.viewRun') }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination class="pager" layout="total, prev, pager, next" background
                   :total="total" v-model:current-page="page" :page-size="20"
                   @current-change="load(false)" />

    <el-card shadow="never" class="mt12">
      <template #header>{{ t('approval.toolTitle') }}</template>
      <el-table :data="toolApprovals" size="small" v-loading="toolLoading"
                :empty-text="t('approval.toolEmpty')">
        <el-table-column prop="id" label="ID" width="65" />
        <el-table-column prop="agentId" label="Agent" width="70" />
        <el-table-column prop="toolName" :label="t('approval.tool')" width="150" />
        <el-table-column :label="t('approval.content')" min-width="220" show-overflow-tooltip>
          <template #default="{ row }"><span class="mono">{{ row.toolInput || '-' }}</span></template>
        </el-table-column>
        <el-table-column :label="t('approval.status')" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="statusType(row.status)">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('approval.result')" min-width="220" show-overflow-tooltip>
          <template #default="{ row }"><span class="mono">{{ row.result || '-' }}</span></template>
        </el-table-column>
        <el-table-column :label="t('approval.submitted')" width="165">
          <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column :label="t('approval.actions')" width="160">
          <template #default="{ row }">
            <template v-if="row.status === 'PENDING'">
              <el-button size="small" type="primary" text @click="decideTool(row, true)">{{ t('approval.approve') }}</el-button>
              <el-button size="small" type="danger" text @click="decideTool(row, false)">{{ t('approval.reject') }}</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="decideVisible" :title="decideApprove ? t('approval.approveTitle') : t('approval.rejectTitle')" width="520px">
      <el-form label-width="80px">
        <el-form-item :label="t('approval.title')">{{ current.title }}</el-form-item>
        <el-form-item :label="t('approval.content')">
          <div class="payload">{{ current.payload || current.runInput }}</div>
        </el-form-item>
        <el-form-item :label="t('approval.comment')">
          <el-input v-model="comment" type="textarea" :rows="2"
                    :placeholder="decideApprove ? t('approval.commentApprovePh') : t('approval.commentRejectPh')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="decideVisible = false">{{ t('approval.cancel') }}</el-button>
        <el-button :type="decideApprove ? 'primary' : 'danger'" :loading="deciding" @click="decide">
          {{ decideApprove ? t('approval.confirmApprove') : t('approval.confirmReject') }}
        </el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="runVisible" :title="t('approval.runDetail')" size="560px">
      <el-descriptions :column="1" border size="small" v-if="run">
        <el-descriptions-item :label="t('approval.runId')">{{ run.id }}</el-descriptions-item>
        <el-descriptions-item :label="t('approval.status')">{{ run.status }} · {{ t('approval.currentNode') }} {{ run.currentNode || '-' }}</el-descriptions-item>
        <el-descriptions-item :label="t('approval.input')">{{ run.input }}</el-descriptions-item>
        <el-descriptions-item :label="t('approval.output')">{{ run.output || run.error }}</el-descriptions-item>
        <el-descriptions-item label="Token">{{ run.inputTokens }}/{{ run.outputTokens }}</el-descriptions-item>
      </el-descriptions>
      <div v-if="run?.nodeRuns?.length" class="mt12">
        <div class="hint">{{ t('approval.track') }}</div>
        <div v-for="n in run.nodeRuns" :key="n.id" class="node-line">
          <el-tag size="small">{{ n.nodeType }}</el-tag>
          <span>{{ n.name || n.nodeId }}</span>
          <el-tag size="small" :type="n.status === 'SUCCESS' ? 'success' : (n.status === 'WAITING' ? 'warning' : 'danger')">
            {{ n.status }}
          </el-tag>
        </div>
      </div>
      <div v-if="run?.checkpoints?.length" class="mt12">
        <div class="hint">{{ t('approval.checkpoints') }}</div>
        <div v-for="cp in run.checkpoints.slice(0, 8)" :key="cp.id" class="node-line">
          <el-tag size="small">{{ cp.status }}</el-tag>
          <span>{{ cp.nodeId || '-' }} → {{ cp.nextNodeId || '-' }}</span>
          <span class="hint">{{ fmt(cp.createdAt) }}</span>
        </div>
      </div>
      <div v-if="run?.status === 'FAILED'" class="mt12">
        <el-button size="small" type="warning" :loading="runResuming" @click="resumeRun">
          {{ t('approval.resume') }}
        </el-button>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'

const { t } = useI18n()

const list = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const status = ref('PENDING')
const loading = ref(false)
const decideVisible = ref(false)
const deciding = ref(false)
const decideApprove = ref(true)
const comment = ref('')
const current = reactive<any>({})
const runVisible = ref(false)
const run = ref<any>(null)
const runResuming = ref(false)
const toolApprovals = ref<any[]>([])
const toolLoading = ref(false)
const toolTotal = ref(0)

onMounted(() => load(true))

async function load(reset: boolean) {
  if (reset) page.value = 1
  loading.value = true
  try {
    const [data, toolData] = await Promise.all([
      http.get('/workflow/approvals', {
        params: { status: status.value === 'ALL' ? undefined : status.value, page: page.value, pageSize: 20 }
      }) as any,
      http.get('/tool-approvals', {
        params: { status: status.value === 'ALL' ? undefined : status.value, page: 1, pageSize: 100 }
      }) as any
    ])
    list.value = data.list || []
    total.value = data.total || 0
    toolApprovals.value = toolData.list || []
    toolTotal.value = toolData.total || 0
  } finally {
    loading.value = false
  }
}

function openDecide(row: any, approve: boolean) {
  Object.assign(current, row)
  decideApprove.value = approve
  comment.value = ''
  decideVisible.value = true
}

async function decide() {
  if (!decideApprove.value && !comment.value.trim()) {
    ElMessage.warning(t('approval.rejectReason'))
    return
  }
  deciding.value = true
  try {
    const data = await http.post(`/workflow/approvals/${current.id}/decide`, {
      approve: decideApprove.value, comment: comment.value
    }, { timeout: 180000 }) as any
    ElMessage.success(decideApprove.value
      ? `${t('approval.approvedToast')}${data.status}`
      : t('approval.rejectedToast'))
    decideVisible.value = false
    await load(true)
  } finally {
    deciding.value = false
  }
}

async function decideTool(row: any, approve: boolean) {
  const action = approve ? t('approval.approve') : t('approval.reject')
  try {
    await ElMessageBox.confirm(
      `${action} ${t('approval.tool')} #${row.id}？${approve ? t('approval.toolApproveHint') : ''}`,
      t('approval.toolTitle'), { type: 'warning',
        confirmButtonText: action, cancelButtonText: t('approval.cancel') })
  } catch {
    return
  }
  try {
    await http.post(`/tool-approvals/${row.id}/decide`, {
      approve, comment: ''
    }, { timeout: 180000 })
    ElMessage.success(approve ? t('approval.toolExecutedToast') : t('approval.rejectedToast'))
    await load(true)
  } catch {
    // 请求拦截器已提示
  }
}

async function viewRun(row: any) {
  run.value = await http.get(`/workflow/runs/${row.runId}`) as any
  runVisible.value = true
}

async function resumeRun() {
  if (!run.value?.id) return
  try {
    await ElMessageBox.confirm(t('approval.resumeConfirm'), t('approval.resumeTitle'), { type: 'warning' })
  } catch {
    return
  }
  runResuming.value = true
  try {
    run.value = await http.post(`/workflow/runs/${run.value.id}/resume?allowSideEffects=true`, {}) as any
    ElMessage.success(t('approval.resumed'))
  } finally {
    runResuming.value = false
  }
}

function statusText(s: string) {
  return ({ PENDING: t('approval.pending'), APPROVED: t('approval.approved'), REJECTED: t('approval.rejected'), EXECUTED: t('approval.executed'), FAILED: t('approval.failed'), EXPIRED: t('approval.expired') } as any)[s] || s
}

function statusType(s: string) {
  if (s === 'APPROVED' || s === 'EXECUTED') return 'success'
  if (s === 'REJECTED' || s === 'FAILED') return 'danger'
  return 'warning'
}

function fmt(v?: string) {
  return v ? String(v).replace('T', ' ').slice(0, 19) : '-'
}
</script>

<style scoped>
.page { background: #fff; border: 1px solid #e4e7ed; border-radius: 6px; padding: 14px; }
.toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
.title { font-weight: 600; font-size: 15px; }
.sub { color: #909399; font-size: 12px; margin-left: 8px; }
.spacer { flex: 1; }
.pager { margin-top: 12px; justify-content: flex-end; }
.payload { white-space: pre-wrap; word-break: break-word; max-height: 200px; overflow-y: auto; font-size: 13px; }
.mt12 { margin-top: 12px; }
.hint { color: #909399; font-size: 12px; margin-bottom: 6px; }
.mono { font-family: monospace; font-size: 12px; }
.node-line { display: flex; align-items: center; gap: 8px; padding: 6px 0; border-bottom: 1px dashed #ebeef5; font-size: 13px; }
</style>