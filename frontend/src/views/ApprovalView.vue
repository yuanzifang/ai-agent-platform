<template>
  <div class="page">
    <div class="toolbar">
      <span class="title">人工审批箱</span>
      <span class="sub">工作流运行到审批节点会在此暂停；审批通过后自动继续执行</span>
      <div class="spacer" />
      <el-select v-model="status" style="width: 140px" @change="load(true)">
        <el-option label="待审批" value="PENDING" />
        <el-option label="已通过" value="APPROVED" />
        <el-option label="已拒绝" value="REJECTED" />
        <el-option label="全部" value="ALL" />
      </el-select>
      <el-button @click="load(true)">刷新</el-button>
    </div>

    <el-table :data="list" size="small" v-loading="loading" empty-text="暂无审批任务">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="agentName" label="Agent" width="150" />
      <el-table-column prop="title" label="审批事项" width="180" />
      <el-table-column label="审批角色" width="100">
        <template #default="{ row }">{{ row.requiredRole || '不限' }}</template>
      </el-table-column>
      <el-table-column label="待审内容" min-width="260" show-overflow-tooltip>
        <template #default="{ row }">{{ row.payload || row.runInput || '-' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="statusType(row.status)">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="提交时间" width="170">
        <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="处理人/意见" min-width="160">
        <template #default="{ row }">{{ row.comment || '-' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
          <template v-if="row.status === 'PENDING'">
            <el-button size="small" type="primary" text @click="openDecide(row, true)">通过</el-button>
            <el-button size="small" type="danger" text @click="openDecide(row, false)">拒绝</el-button>
          </template>
          <el-button size="small" text @click="viewRun(row)">查看运行</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination class="pager" layout="total, prev, pager, next" background
                   :total="total" v-model:current-page="page" :page-size="20"
                   @current-change="load(false)" />

    <el-dialog v-model="decideVisible" :title="decideApprove ? '通过审批' : '拒绝审批'" width="520px">
      <el-form label-width="80px">
        <el-form-item label="审批事项">{{ current.title }}</el-form-item>
        <el-form-item label="内容">
          <div class="payload">{{ current.payload || current.runInput }}</div>
        </el-form-item>
        <el-form-item label="审批意见">
          <el-input v-model="comment" type="textarea" :rows="2"
                    :placeholder="decideApprove ? '可选，如：同意退款' : '必填，如：金额超限，转人工'" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="decideVisible = false">取消</el-button>
        <el-button :type="decideApprove ? 'primary' : 'danger'" :loading="deciding" @click="decide">
          {{ decideApprove ? '确认通过' : '确认拒绝' }}
        </el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="runVisible" title="运行详情" size="560px">
      <el-descriptions :column="1" border size="small" v-if="run">
        <el-descriptions-item label="运行 ID">{{ run.id }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ run.status }} · 当前节点 {{ run.currentNode || '-' }}</el-descriptions-item>
        <el-descriptions-item label="输入">{{ run.input }}</el-descriptions-item>
        <el-descriptions-item label="输出">{{ run.output || run.error }}</el-descriptions-item>
        <el-descriptions-item label="Token">{{ run.inputTokens }}/{{ run.outputTokens }}</el-descriptions-item>
      </el-descriptions>
      <div v-if="run?.nodeRuns?.length" class="mt12">
        <div class="hint">节点轨迹</div>
        <div v-for="n in run.nodeRuns" :key="n.id" class="node-line">
          <el-tag size="small">{{ n.nodeType }}</el-tag>
          <span>{{ n.name || n.nodeId }}</span>
          <el-tag size="small" :type="n.status === 'SUCCESS' ? 'success' : (n.status === 'WAITING' ? 'warning' : 'danger')">
            {{ n.status }}
          </el-tag>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api/http'

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

onMounted(() => load(true))

async function load(reset: boolean) {
  if (reset) page.value = 1
  loading.value = true
  try {
    const data = await http.get('/workflow/approvals', {
      params: { status: status.value === 'ALL' ? undefined : status.value, page: page.value, pageSize: 20 }
    }) as any
    list.value = data.list || []
    total.value = data.total || 0
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
    ElMessage.warning('拒绝时请填写原因')
    return
  }
  deciding.value = true
  try {
    const data = await http.post(`/workflow/approvals/${current.id}/decide`, {
      approve: decideApprove.value, comment: comment.value
    }, { timeout: 180000 }) as any
    ElMessage.success(decideApprove.value
      ? `已通过，运行状态：${data.status}`
      : '已拒绝')
    decideVisible.value = false
    await load(true)
  } finally {
    deciding.value = false
  }
}

async function viewRun(row: any) {
  run.value = await http.get(`/workflow/runs/${row.runId}`) as any
  runVisible.value = true
}

function statusText(s: string) {
  return ({ PENDING: '待审批', APPROVED: '已通过', REJECTED: '已拒绝' } as any)[s] || s
}

function statusType(s: string) {
  if (s === 'APPROVED') return 'success'
  if (s === 'REJECTED') return 'danger'
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
.node-line { display: flex; align-items: center; gap: 8px; padding: 6px 0; border-bottom: 1px dashed #ebeef5; font-size: 13px; }
</style>
