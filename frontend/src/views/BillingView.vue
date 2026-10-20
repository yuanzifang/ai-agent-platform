<template>
  <div class="page">
    <div class="toolbar">
      <span class="title">计费管理</span>
      <span class="sub">规则/账单/充值/配额；成本口径：billing_rule 优先，未配置回落模型单价</span>
      <div class="spacer" />
      <el-button v-if="canManage" type="primary" @click="openRecharge">充值</el-button>
    </div>

    <el-alert v-if="account.lowBalance" type="warning" show-icon :closable="false" class="mb">
      <template #title>可用余额 ¥{{ money(account.balance) }} 低于阈值 ¥{{ money(account.threshold) }}，请及时充值</template>
    </el-alert>

    <el-tabs v-model="tab">
      <!-- 概览 -->
      <el-tab-pane label="概览" name="overview">
        <div class="cards">
          <el-card shadow="never"><div class="k">累计充值/调整</div><div class="v">¥{{ money(account.totalRecharged) }}</div></el-card>
          <el-card shadow="never"><div class="k">已消耗</div><div class="v">¥{{ money(account.totalCost) }}</div></el-card>
          <el-card shadow="never"><div class="k">可用余额</div>
            <div class="v" :class="{ danger: account.lowBalance }">¥{{ money(account.balance) }}</div>
          </el-card>
          <el-card shadow="never"><div class="k">币种 / 低余额阈值</div><div class="v small">{{ account.currency }} / ¥{{ money(account.threshold) }}</div></el-card>
        </div>
        <el-card shadow="never" class="mt">
          <template #header>最近账单</template>
          <el-table :data="bills.slice(0, 5)" size="small">
            <el-table-column prop="period" label="账期" width="100" />
            <el-table-column prop="calls" label="调用次数" width="100" />
            <el-table-column label="Tokens" width="120">
              <template #default="{ row }">{{ fmt(row.totalTokens) }}</template>
            </el-table-column>
            <el-table-column label="成本" width="120">
              <template #default="{ row }">¥{{ money(row.cost) }}</template>
            </el-table-column>
            <el-table-column label="生成时间">
              <template #default="{ row }">{{ fmtTime(row.generatedAt) }}</template>
            </el-table-column>
          </el-table>
          <el-empty v-if="bills.length === 0" description="暂无账单，请在「账单」页生成" :image-size="60" />
        </el-card>
      </el-tab-pane>

      <!-- 计费规则 -->
      <el-tab-pane label="计费规则" name="rules">
        <div class="tab-actions">
          <span class="hint">精确模型规则优先；空模型名 = 租户默认规则；规则变更后下次调用生效</span>
          <div class="spacer" />
          <el-button v-if="canRuleManage" type="primary" size="small" @click="openRuleCreate">新增规则</el-button>
        </div>
        <el-table :data="rules" size="small">
          <el-table-column label="模型" width="180">
            <template #default="{ row }">{{ row.modelName || '（默认规则）' }}</template>
          </el-table-column>
          <el-table-column prop="billingMode" label="计费方式" width="100" />
          <el-table-column label="输入单价" width="120">
            <template #default="{ row }">{{ row.billingMode === 'COUNT' ? '-' : '¥' + money(row.inputPrice) + '/千Token' }}</template>
          </el-table-column>
          <el-table-column label="输出单价" width="120">
            <template #default="{ row }">{{ row.billingMode === 'COUNT' ? '-' : '¥' + money(row.outputPrice) + '/千Token' }}</template>
          </el-table-column>
          <el-table-column label="缓存命中/写入" width="150">
            <template #default="{ row }">
              {{ row.billingMode === 'COUNT' ? '-' : '¥' + money(row.cacheReadPrice) + ' / ¥' + money(row.cacheWritePrice) }}
            </template>
          </el-table-column>
          <el-table-column label="单次价格" width="110">
            <template #default="{ row }">{{ row.billingMode === 'COUNT' ? '¥' + money(row.pricePerCall) + '/次' : '-' }}</template>
          </el-table-column>
          <el-table-column prop="currency" label="币种" width="80" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'ENABLED' ? 'success' : 'info'">
                {{ row.status === 'ENABLED' ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column v-if="canRuleManage" label="操作" width="130">
            <template #default="{ row }">
              <el-button size="small" text @click="openRuleEdit(row)">编辑</el-button>
              <el-button size="small" text type="danger" @click="removeRule(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 账单 -->
      <el-tab-pane label="账单" name="bills">
        <div class="tab-actions">
          <el-select v-model="billPeriodType" style="width: 90px" @change="switchPeriodType">
            <el-option label="月账单" value="month" />
            <el-option label="周账单" value="week" />
          </el-select>
          <el-date-picker v-if="billPeriodType === 'month'" v-model="billPeriod" type="month"
                          value-format="YYYY-MM" placeholder="账期" style="width: 150px" />
          <el-date-picker v-else v-model="billPeriod" type="week" format="YYYY 第 ww 周"
                          value-format="YYYY-[W]WW" placeholder="账期" style="width: 170px" />
          <el-button v-if="canManage" type="primary" size="small" :loading="generating" @click="generateBill">生成账单</el-button>
          <span class="hint">按月生成；同账期重复生成将覆盖汇总</span>
        </div>
        <el-table :data="bills" size="small">
          <el-table-column prop="period" label="账期" width="100" />
          <el-table-column prop="calls" label="调用次数" width="100" />
          <el-table-column label="输入 Tokens" width="130">
            <template #default="{ row }">{{ fmt(row.inputTokens) }}</template>
          </el-table-column>
          <el-table-column label="输出 Tokens" width="130">
            <template #default="{ row }">{{ fmt(row.outputTokens) }}</template>
          </el-table-column>
          <el-table-column label="成本" width="120">
            <template #default="{ row }">¥{{ money(row.cost) }}</template>
          </el-table-column>
          <el-table-column label="生成时间" width="170">
            <template #default="{ row }">{{ fmtTime(row.generatedAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="180">
            <template #default="{ row }">
              <el-button size="small" text @click="openBill(row)">明细</el-button>
              <el-button size="small" text type="primary" @click="exportBill(row)">导出 CSV</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="bills.length === 0" description="暂无账单" :image-size="60" />
      </el-tab-pane>

      <!-- 充值记录 -->
      <el-tab-pane label="充值记录" name="transactions">
        <el-table :data="transactions" size="small">
          <el-table-column label="时间" width="170">
            <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column prop="type" label="类型" width="100" />
          <el-table-column label="金额" width="120">
            <template #default="{ row }">¥{{ money(row.amount) }}</template>
          </el-table-column>
          <el-table-column label="累计充值后" width="140">
            <template #default="{ row }">¥{{ money(row.balanceAfter) }}</template>
          </el-table-column>
          <el-table-column prop="operatorId" label="操作人" width="90" />
          <el-table-column prop="note" label="备注" min-width="200" />
        </el-table>
        <el-empty v-if="transactions.length === 0" description="暂无充值记录" :image-size="60" />
      </el-tab-pane>

      <!-- 配额 -->
      <el-tab-pane label="配额" name="quota">
        <div class="tab-actions">
          <span class="hint">0 表示不限制；使用率达到 80% 触发告警</span>
          <div class="spacer" />
          <el-button v-if="canManage" size="small" type="primary" @click="openQuota">调整配额</el-button>
        </div>
        <el-alert v-for="(w, i) in quota.warnings || []" :key="i" type="warning" :title="w"
                  show-icon :closable="false" class="mb6" />
        <div class="quota-grid">
          <div v-for="item in quotaItems" :key="item.label" class="quota-item">
            <div class="q-head">
              <span>{{ item.label }}</span>
              <span class="q-num">{{ item.text }}</span>
            </div>
            <el-progress :percentage="item.percent" :stroke-width="8"
                         :status="item.percent >= 100 ? 'exception' : (item.percent >= 80 ? 'warning' : '')" />
          </div>
        </div>
        <div class="cards mt">
          <el-card shadow="never"><div class="k">Agent 数量</div><div class="v">{{ quota.usage?.agents ?? 0 }} / {{ limitText(quota.quota?.maxAgents) }}</div></el-card>
          <el-card shadow="never"><div class="k">知识库数量</div><div class="v">{{ quota.usage?.knowledgeBases ?? 0 }} / {{ limitText(quota.quota?.maxKnowledgeBases) }}</div></el-card>
          <el-card shadow="never"><div class="k">今日 Tokens</div><div class="v">{{ fmt(quota.usage?.tokensToday) }} / {{ fmt(quota.quota?.maxTokensPerDay) }}</div></el-card>
          <el-card shadow="never"><div class="k">存储</div><div class="v">{{ mb(quota.usage?.storageBytes) }} / {{ mb(quota.quota?.maxStorageBytes) }}</div></el-card>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 规则弹窗 -->
    <el-dialog v-model="ruleVisible" :title="ruleForm.id ? '编辑计费规则' : '新增计费规则'" width="560px">
      <el-form label-width="100px">
        <el-form-item label="模型">
          <el-input v-model="ruleForm.modelName" placeholder="留空 = 租户默认规则（如 deepseek-flash）" />
        </el-form-item>
        <el-form-item label="计费方式">
          <el-select v-model="ruleForm.billingMode" style="width: 160px">
            <el-option label="按 Token" value="TOKEN" />
            <el-option label="按次数" value="COUNT" />
          </el-select>
        </el-form-item>
        <template v-if="ruleForm.billingMode === 'TOKEN'">
          <el-form-item label="输入单价">
            <el-input-number v-model="ruleForm.inputPrice" :min="0" :precision="6" :step="0.001" />
            <span class="unit">元 / 千 Token</span>
          </el-form-item>
          <el-form-item label="输出单价">
            <el-input-number v-model="ruleForm.outputPrice" :min="0" :precision="6" :step="0.001" />
            <span class="unit">元 / 千 Token</span>
          </el-form-item>
          <el-form-item label="缓存命中单价">
            <el-input-number v-model="ruleForm.cacheReadPrice" :min="0" :precision="6" :step="0.001" />
            <span class="unit">元 / 千 Token（未配置则按输入单价）</span>
          </el-form-item>
          <el-form-item label="缓存写入单价">
            <el-input-number v-model="ruleForm.cacheWritePrice" :min="0" :precision="6" :step="0.001" />
            <span class="unit">元 / 千 Token（未配置则 0）</span>
          </el-form-item>
        </template>
        <el-form-item v-else label="单次价格">
          <el-input-number v-model="ruleForm.pricePerCall" :min="0" :precision="6" :step="0.01" />
          <span class="unit">元 / 次</span>
        </el-form-item>
        <el-form-item label="币种">
          <el-input v-model="ruleForm.currency" style="width: 120px" maxlength="3" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="ruleForm.enabled" active-text="启用" inactive-text="停用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ruleVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveRule">保存</el-button>
      </template>
    </el-dialog>

    <!-- 充值弹窗 -->
    <el-dialog v-model="rechargeVisible" title="账户充值" width="440px">
      <el-form label-width="80px">
        <el-form-item label="金额" required>
          <el-input-number v-model="rechargeForm.amount" :min="0.01" :precision="2" :step="10" />
          <span class="unit">元</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="rechargeForm.note" placeholder="如：对公转账 / 测试充值" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rechargeVisible = false">取消</el-button>
        <el-button type="primary" :loading="recharging" @click="doRecharge">确认充值</el-button>
      </template>
    </el-dialog>

    <!-- 账单明细 -->
    <el-drawer v-model="billVisible" :title="`账单明细：${bill?.period || ''}`" size="640px">
      <div v-if="bill" class="bill-summary">
        <span>调用 {{ bill.calls }} 次</span>
        <span>Tokens {{ fmt(bill.totalTokens) }}</span>
        <span>成本 ¥{{ money(bill.cost) }}</span>
      </div>
      <el-table :data="billRows" size="small" max-height="520">
        <el-table-column label="时间" width="160">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column prop="source" label="来源" width="80" />
        <el-table-column prop="modelName" label="模型" width="130" />
        <el-table-column prop="inputTokens" label="输入" width="90" />
        <el-table-column prop="outputTokens" label="输出" width="90" />
        <el-table-column label="成本" width="100">
          <template #default="{ row }">¥{{ money(row.cost) }}</template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <!-- 配额调整 -->
    <el-dialog v-model="quotaVisible" title="调整配额（0 = 不限制）" width="520px">
      <el-form label-width="130px">
        <el-form-item label="Agent 上限"><el-input-number v-model="quotaForm.maxAgents" :min="0" /></el-form-item>
        <el-form-item label="知识库上限"><el-input-number v-model="quotaForm.maxKnowledgeBases" :min="0" /></el-form-item>
        <el-form-item label="每日 Token 上限"><el-input-number v-model="quotaForm.maxTokensPerDay" :min="0" :step="100000" /></el-form-item>
        <el-form-item label="存储上限(字节)"><el-input-number v-model="quotaForm.maxStorageBytes" :min="0" :step="1073741824" /></el-form-item>
        <el-form-item label="每分钟 API 调用"><el-input-number v-model="quotaForm.maxApiCallsPerMin" :min="0" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="quotaVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingQuota" @click="saveQuota">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'
import { useUserStore } from '../stores/user'

const userStore = useUserStore()
const perms = computed(() => userStore.userInfo?.permissions || [])
const canManage = computed(() => userStore.userInfo?.isPlatformAdmin || perms.value.includes('tenant:manage'))
const canRuleManage = computed(() => userStore.userInfo?.isPlatformAdmin || perms.value.includes('model:manage'))

const tab = ref('overview')
const account = reactive<any>({ totalRecharged: 0, totalCost: 0, balance: 0, threshold: 10, lowBalance: false, currency: 'CNY' })
const rules = ref<any[]>([])
const bills = ref<any[]>([])
const transactions = ref<any[]>([])
const quota = reactive<any>({ quota: {}, usage: {}, warnings: [] })
const billPeriodType = ref('month')
const billPeriod = ref(new Date().toISOString().slice(0, 7))
function switchPeriodType() {
  billPeriod.value = billPeriodType.value === 'month'
    ? new Date().toISOString().slice(0, 7)
    : ''
}

const ruleVisible = ref(false)
const saving = ref(false)
const ruleForm = reactive({
  id: 0, modelName: '', billingMode: 'TOKEN', inputPrice: 0, outputPrice: 0,
  cacheReadPrice: 0, cacheWritePrice: 0, pricePerCall: 0, currency: 'CNY', enabled: true
})

const rechargeVisible = ref(false)
const recharging = ref(false)
const rechargeForm = reactive({ amount: 100, note: '' })

const billVisible = ref(false)
const bill = ref<any>(null)
const billRows = ref<any[]>([])

const generating = ref(false)
const quotaVisible = ref(false)
const savingQuota = ref(false)
const quotaForm = reactive({
  maxAgents: 10, maxKnowledgeBases: 5, maxTokensPerDay: 1000000,
  maxStorageBytes: 10737418240, maxApiCallsPerMin: 60
})

const quotaItems = computed(() => {
  const q = quota.quota || {}
  const u = quota.usage || {}
  const items = [
    { label: 'Agent', used: u.agents || 0, max: q.maxAgents || 0 },
    { label: '知识库', used: u.knowledgeBases || 0, max: q.maxKnowledgeBases || 0 },
    { label: '今日 Token', used: u.tokensToday || 0, max: q.maxTokensPerDay || 0 },
    { label: '存储（MB）', used: Math.round((u.storageBytes || 0) / 1048576), max: Math.round((q.maxStorageBytes || 0) / 1048576) }
  ]
  return items.map(it => ({
    ...it,
    percent: it.max > 0 ? Math.min(100, Math.round(it.used * 100 / it.max)) : 0,
    text: it.max > 0 ? `${it.used} / ${it.max}` : `${it.used} / 不限制`
  }))
})

onMounted(loadAll)

async function loadAll() {
  const [acc, rl, bl, tx, q] = await Promise.all([
    http.get('/billing/account') as any,
    http.get('/billing/rules') as any,
    http.get('/billing/bills') as any,
    http.get('/billing/transactions', { params: { pageSize: 50 } }) as any,
    http.get('/billing/quota') as any
  ])
  Object.assign(account, acc || {})
  rules.value = rl?.list || []
  bills.value = bl?.list || []
  transactions.value = tx?.list || []
  Object.assign(quota, q || {})
}

function money(v?: number | string) {
  const n = Number(v || 0)
  return n.toFixed(n !== 0 && Math.abs(n) < 0.01 ? 6 : 4)
}

function fmt(v?: number | string) {
  return Number(v || 0).toLocaleString('zh-CN')
}

function fmtTime(v?: string) {
  return v ? String(v).replace('T', ' ').slice(0, 19) : '-'
}

function limitText(v?: number) {
  return v ? v : '不限制'
}

function mb(v?: number) {
  const n = Number(v || 0)
  if (n <= 0) return '不限制'
  if (n < 1048576) return (n / 1024).toFixed(1) + ' KB'
  if (n < 1073741824) return (n / 1048576).toFixed(1) + ' MB'
  return (n / 1073741824).toFixed(2) + ' GB'
}

function openRuleCreate() {
  Object.assign(ruleForm, { id: 0, modelName: '', billingMode: 'TOKEN', inputPrice: 0,
    outputPrice: 0, cacheReadPrice: 0, cacheWritePrice: 0, pricePerCall: 0, currency: 'CNY', enabled: true })
  ruleVisible.value = true
}

function openRuleEdit(row: any) {
  Object.assign(ruleForm, {
    id: row.id, modelName: row.modelName || '', billingMode: row.billingMode || 'TOKEN',
    inputPrice: Number(row.inputPrice || 0), outputPrice: Number(row.outputPrice || 0),
    cacheReadPrice: Number(row.cacheReadPrice || 0), cacheWritePrice: Number(row.cacheWritePrice || 0),
    pricePerCall: Number(row.pricePerCall || 0), currency: row.currency || 'CNY',
    enabled: row.status !== 'DISABLED'
  })
  ruleVisible.value = true
}

async function saveRule() {
  saving.value = true
  try {
    const payload = {
      modelName: ruleForm.modelName || null,
      billingMode: ruleForm.billingMode,
      inputPrice: ruleForm.inputPrice,
      outputPrice: ruleForm.outputPrice,
      cacheReadPrice: ruleForm.cacheReadPrice,
      cacheWritePrice: ruleForm.cacheWritePrice,
      pricePerCall: ruleForm.pricePerCall,
      currency: ruleForm.currency || 'CNY',
      status: ruleForm.enabled ? 'ENABLED' : 'DISABLED'
    }
    if (ruleForm.id) await http.put(`/billing/rules/${ruleForm.id}`, payload)
    else await http.post('/billing/rules', payload)
    ElMessage.success('已保存')
    ruleVisible.value = false
    rules.value = (await http.get('/billing/rules') as any)?.list || []
  } finally {
    saving.value = false
  }
}

async function removeRule(row: any) {
  await ElMessageBox.confirm(`删除模型「${row.modelName || '默认规则'}」的计费规则？删除后回落到模型单价。`,
    '删除计费规则', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  await http.delete(`/billing/rules/${row.id}`)
  ElMessage.success('已删除')
  rules.value = (await http.get('/billing/rules') as any)?.list || []
}

function openRecharge() {
  rechargeForm.amount = 100
  rechargeForm.note = ''
  rechargeVisible.value = true
}

async function doRecharge() {
  recharging.value = true
  try {
    const acc = await http.post('/billing/recharge', {
      amount: rechargeForm.amount, note: rechargeForm.note
    }) as any
    Object.assign(account, acc || {})
    rechargeVisible.value = false
    ElMessage.success('充值成功')
    transactions.value = ((await http.get('/billing/transactions', { params: { pageSize: 50 } })) as any)?.list || []
  } finally {
    recharging.value = false
  }
}

async function generateBill() {
  if (!billPeriod.value) { ElMessage.warning('请选择账期'); return }
  generating.value = true
  try {
    const res = await http.post('/billing/bills/generate', { period: billPeriod.value }) as any
    ElMessage.success(`已生成 ${res.period} 账单：¥${money(res.cost)}`)
    bills.value = (await http.get('/billing/bills') as any)?.list || []
  } finally {
    generating.value = false
  }
}

async function openBill(row: any) {
  const res = await http.get(`/billing/bills/${row.id}`, { params: { pageSize: 100 } }) as any
  bill.value = res.bill
  billRows.value = res.list || []
  billVisible.value = true
}

async function exportBill(row: any) {
  const blob = await http.get(`/billing/bills/${row.id}/export`, { responseType: 'blob' }) as any
  const url = URL.createObjectURL(new Blob([blob], { type: 'text/csv;charset=utf-8' }))
  const a = document.createElement('a')
  a.href = url
  a.download = `bill-${row.period}.csv`
  a.click()
  URL.revokeObjectURL(url)
}

function openQuota() {
  const q = quota.quota || {}
  Object.assign(quotaForm, {
    maxAgents: q.maxAgents ?? 10,
    maxKnowledgeBases: q.maxKnowledgeBases ?? 5,
    maxTokensPerDay: q.maxTokensPerDay ?? 1000000,
    maxStorageBytes: q.maxStorageBytes ?? 10737418240,
    maxApiCallsPerMin: q.maxApiCallsPerMin ?? 60
  })
  quotaVisible.value = true
}

async function saveQuota() {
  savingQuota.value = true
  try {
    const res = await http.put('/billing/quota', { ...quotaForm }) as any
    Object.assign(quota, res || {})
    quotaVisible.value = false
    ElMessage.success('配额已更新')
  } finally {
    savingQuota.value = false
  }
}
</script>

<style scoped>
.page { background: #fff; border: 1px solid #e4e7ed; border-radius: 6px; padding: 14px; }
.toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.title { font-weight: 600; font-size: 15px; }
.sub { color: #909399; font-size: 12px; margin-left: 8px; }
.spacer { flex: 1; }
.cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(190px, 1fr)); gap: 12px; }
.cards .k { color: #909399; font-size: 12px; }
.cards .v { font-size: 22px; font-weight: 600; margin-top: 6px; }
.cards .v.small { font-size: 15px; }
.cards .v.danger { color: #f56c6c; }
.mt { margin-top: 12px; }
.mb { margin-bottom: 12px; }
.mb6 { margin-bottom: 6px; }
.tab-actions { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.hint { color: #909399; font-size: 12px; }
.unit { color: #909399; font-size: 12px; margin-left: 8px; }
.quota-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 14px; }
.quota-item { border: 1px solid #ebeef5; border-radius: 6px; padding: 10px 12px; }
.q-head { display: flex; justify-content: space-between; font-size: 13px; margin-bottom: 8px; }
.q-num { color: #606266; }
.bill-summary { display: flex; gap: 20px; color: #606266; font-size: 13px; margin-bottom: 10px; }
</style>
