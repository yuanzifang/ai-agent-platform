<template>
  <div class="page">
    <div class="toolbar">
      <span class="title">{{ t('page.billing') }}</span>
      <span class="sub">{{ t('billingPage.sub') }}</span>
      <div class="spacer" />
      <el-button v-if="canManage" type="primary" @click="openRecharge">{{ t('billingPage.recharge') }}</el-button>
    </div>

    <el-alert v-if="account.lowBalance" type="warning" show-icon :closable="false" class="mb">
      <template #title>{{ t('billingPage.lowBalancePrefix') }}{{ money(account.balance) }}{{ t('billingPage.lowBalanceMid') }}{{ money(account.threshold) }}{{ t('billingPage.lowBalanceSuffix') }}</template>
    </el-alert>

    <el-tabs v-model="tab">
      <!-- 概览 -->
      <el-tab-pane :label="t('billingPage.overview')" name="overview">
        <div class="cards">
          <el-card shadow="never"><div class="k">{{ t('billingPage.totalRecharged') }}</div><div class="v">¥{{ money(account.totalRecharged) }}</div></el-card>
          <el-card shadow="never"><div class="k">{{ t('billingPage.consumed') }}</div><div class="v">¥{{ money(account.totalCost) }}</div></el-card>
          <el-card shadow="never"><div class="k">{{ t('billingPage.balance') }}</div>
            <div class="v" :class="{ danger: account.lowBalance }">¥{{ money(account.balance) }}</div>
          </el-card>
          <el-card shadow="never"><div class="k">{{ t('billingPage.currencyThreshold') }}</div><div class="v small">{{ account.currency }} / ¥{{ money(account.threshold) }}</div></el-card>
        </div>
        <el-card shadow="never" class="mt">
          <template #header>{{ t('billingPage.recentBills') }}</template>
          <el-table :data="bills.slice(0, 5)" size="small">
            <el-table-column prop="period" :label="t('billingPage.period')" width="100" />
            <el-table-column prop="calls" :label="t('billingPage.calls')" width="100" />
            <el-table-column label="Tokens" width="120">
              <template #default="{ row }">{{ fmt(row.totalTokens) }}</template>
            </el-table-column>
            <el-table-column :label="t('billingPage.cost')" width="120">
              <template #default="{ row }">¥{{ money(row.cost) }}</template>
            </el-table-column>
            <el-table-column :label="t('billingPage.generatedAt')">
              <template #default="{ row }">{{ fmtTime(row.generatedAt) }}</template>
            </el-table-column>
          </el-table>
          <el-empty v-if="bills.length === 0" :description="t('billingPage.noBillsGenerate')" :image-size="60" />
        </el-card>
      </el-tab-pane>

      <!-- 计费规则 -->
      <el-tab-pane :label="t('billingPage.rules')" name="rules">
        <div class="tab-actions">
          <span class="hint">{{ t('billingPage.rulesHint') }}</span>
          <div class="spacer" />
          <el-button v-if="canRuleManage" type="primary" size="small" @click="openRuleCreate">{{ t('billingPage.addRule') }}</el-button>
        </div>
        <el-table :data="rules" size="small">
          <el-table-column :label="t('billingPage.model')" width="180">
            <template #default="{ row }">{{ row.modelName || t('billingPage.defaultRule') }}</template>
          </el-table-column>
          <el-table-column prop="billingMode" :label="t('billingPage.mode')" width="100" />
          <el-table-column :label="t('billingPage.inputPrice')" width="120">
            <template #default="{ row }">{{ row.billingMode === 'COUNT' ? '-' : '¥' + money(row.inputPrice) + t('billingPage.perKShort') }}</template>
          </el-table-column>
          <el-table-column :label="t('billingPage.outputPrice')" width="120">
            <template #default="{ row }">{{ row.billingMode === 'COUNT' ? '-' : '¥' + money(row.outputPrice) + t('billingPage.perKShort') }}</template>
          </el-table-column>
          <el-table-column :label="t('billingPage.cache')" width="150">
            <template #default="{ row }">
              {{ row.billingMode === 'COUNT' ? '-' : '¥' + money(row.cacheReadPrice) + ' / ¥' + money(row.cacheWritePrice) }}
            </template>
          </el-table-column>
          <el-table-column :label="t('billingPage.perCall')" width="110">
            <template #default="{ row }">{{ row.billingMode === 'COUNT' ? '¥' + money(row.pricePerCall) + t('billingPage.perCallShort') : '-' }}</template>
          </el-table-column>
          <el-table-column prop="currency" :label="t('billingPage.currency')" width="80" />
          <el-table-column :label="t('billingPage.status')" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'ENABLED' ? 'success' : 'info'">
                {{ row.status === 'ENABLED' ? t('billingPage.enabled') : t('billingPage.disabled') }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column v-if="canRuleManage" :label="t('billingPage.actions')" width="130">
            <template #default="{ row }">
              <el-button size="small" text @click="openRuleEdit(row)">{{ t('billingPage.edit') }}</el-button>
              <el-button size="small" text type="danger" @click="removeRule(row)">{{ t('billingPage.delete') }}</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 账单 -->
      <el-tab-pane :label="t('billingPage.bills')" name="bills">
        <div class="tab-actions">
          <el-select v-model="billPeriodType" style="width: 90px" @change="switchPeriodType">
            <el-option :label="t('billingPage.month')" value="month" />
            <el-option :label="t('billingPage.week')" value="week" />
          </el-select>
          <el-date-picker v-if="billPeriodType === 'month'" v-model="billPeriod" type="month"
                          value-format="YYYY-MM" :placeholder="t('billingPage.period')" style="width: 150px" />
          <el-date-picker v-else v-model="billPeriod" type="week" format="YYYY-Www"
                          value-format="YYYY-[W]WW" :placeholder="t('billingPage.period')" style="width: 170px" />
          <el-button v-if="canManage" type="primary" size="small" :loading="generating" @click="generateBill">{{ t('billingPage.generate') }}</el-button>
          <span class="hint">{{ t('billingPage.generateHint') }}</span>
        </div>
        <el-table :data="bills" size="small">
          <el-table-column prop="period" :label="t('billingPage.period')" width="100" />
          <el-table-column prop="calls" :label="t('billingPage.calls')" width="100" />
          <el-table-column :label="t('billingPage.inputTokens')" width="130">
            <template #default="{ row }">{{ fmt(row.inputTokens) }}</template>
          </el-table-column>
          <el-table-column :label="t('billingPage.outputTokens')" width="130">
            <template #default="{ row }">{{ fmt(row.outputTokens) }}</template>
          </el-table-column>
          <el-table-column :label="t('billingPage.cost')" width="120">
            <template #default="{ row }">¥{{ money(row.cost) }}</template>
          </el-table-column>
          <el-table-column :label="t('billingPage.generatedAt')" width="170">
            <template #default="{ row }">{{ fmtTime(row.generatedAt) }}</template>
          </el-table-column>
          <el-table-column :label="t('billingPage.actions')" width="180">
            <template #default="{ row }">
              <el-button size="small" text @click="openBill(row)">{{ t('billingPage.detail') }}</el-button>
              <el-button size="small" text type="primary" @click="exportBill(row)">{{ t('billingPage.exportCsv') }}</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="bills.length === 0" :description="t('billingPage.noBills')" :image-size="60" />
      </el-tab-pane>

      <!-- 充值记录 -->
      <el-tab-pane :label="t('billingPage.transactions')" name="transactions">
        <el-table :data="transactions" size="small">
          <el-table-column :label="t('billingPage.time')" width="170">
            <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column prop="type" :label="t('billingPage.type')" width="100" />
          <el-table-column :label="t('billingPage.amount')" width="120">
            <template #default="{ row }">¥{{ money(row.amount) }}</template>
          </el-table-column>
          <el-table-column :label="t('billingPage.balanceAfter')" width="140">
            <template #default="{ row }">¥{{ money(row.balanceAfter) }}</template>
          </el-table-column>
          <el-table-column prop="operatorId" :label="t('billingPage.operator')" width="90" />
          <el-table-column prop="note" :label="t('billingPage.note')" min-width="200" />
        </el-table>
        <el-empty v-if="transactions.length === 0" :description="t('billingPage.noTx')" :image-size="60" />
      </el-tab-pane>

      <!-- 配额 -->
      <el-tab-pane :label="t('billingPage.quota')" name="quota">
        <div class="tab-actions">
          <span class="hint">{{ t('billingPage.quotaHint') }}</span>
          <div class="spacer" />
          <el-button v-if="canManage" size="small" type="primary" @click="openQuota">{{ t('billingPage.adjustQuota') }}</el-button>
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
          <el-card shadow="never"><div class="k">{{ t('billingPage.agentCount') }}</div><div class="v">{{ quota.usage?.agents ?? 0 }} / {{ limitText(quota.quota?.maxAgents) }}</div></el-card>
          <el-card shadow="never"><div class="k">{{ t('billingPage.kbCount') }}</div><div class="v">{{ quota.usage?.knowledgeBases ?? 0 }} / {{ limitText(quota.quota?.maxKnowledgeBases) }}</div></el-card>
          <el-card shadow="never"><div class="k">{{ t('billingPage.todayTokens') }}</div><div class="v">{{ fmt(quota.usage?.tokensToday) }} / {{ fmt(quota.quota?.maxTokensPerDay) }}</div></el-card>
          <el-card shadow="never"><div class="k">{{ t('billingPage.storage') }}</div><div class="v">{{ mb(quota.usage?.storageBytes) }} / {{ mb(quota.quota?.maxStorageBytes) }}</div></el-card>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 规则弹窗 -->
    <el-dialog v-model="ruleVisible" :title="ruleForm.id ? t('billingPage.ruleEdit') : t('billingPage.ruleNew')" width="560px">
      <el-form label-width="100px">
        <el-form-item :label="t('billingPage.model')">
          <el-input v-model="ruleForm.modelName" :placeholder="t('billingPage.modelPh')" />
        </el-form-item>
        <el-form-item :label="t('billingPage.mode')">
          <el-select v-model="ruleForm.billingMode" style="width: 160px">
            <el-option :label="t('billingPage.modeToken')" value="TOKEN" />
            <el-option :label="t('billingPage.modeCount')" value="COUNT" />
          </el-select>
        </el-form-item>
        <template v-if="ruleForm.billingMode === 'TOKEN'">
          <el-form-item :label="t('billingPage.inputPrice')">
            <el-input-number v-model="ruleForm.inputPrice" :min="0" :precision="6" :step="0.001" />
            <span class="unit">{{ t('billingPage.inputUnit') }}</span>
          </el-form-item>
          <el-form-item :label="t('billingPage.outputPrice')">
            <el-input-number v-model="ruleForm.outputPrice" :min="0" :precision="6" :step="0.001" />
            <span class="unit">{{ t('billingPage.inputUnit') }}</span>
          </el-form-item>
          <el-form-item :label="t('billingPage.cacheReadPrice')">
            <el-input-number v-model="ruleForm.cacheReadPrice" :min="0" :precision="6" :step="0.001" />
            <span class="unit">{{ t('billingPage.cacheReadUnit') }}</span>
          </el-form-item>
          <el-form-item :label="t('billingPage.cacheWritePrice')">
            <el-input-number v-model="ruleForm.cacheWritePrice" :min="0" :precision="6" :step="0.001" />
            <span class="unit">{{ t('billingPage.cacheWriteUnit') }}</span>
          </el-form-item>
        </template>
        <el-form-item v-else :label="t('billingPage.perCall')">
          <el-input-number v-model="ruleForm.pricePerCall" :min="0" :precision="6" :step="0.01" />
          <span class="unit">{{ t('billingPage.perCallUnit') }}</span>
        </el-form-item>
        <el-form-item :label="t('billingPage.currency')">
          <el-input v-model="ruleForm.currency" style="width: 120px" maxlength="3" />
        </el-form-item>
        <el-form-item :label="t('billingPage.status')">
          <el-switch v-model="ruleForm.enabled" :active-text="t('billingPage.enabled')" :inactive-text="t('billingPage.disabled')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ruleVisible = false">{{ t('billingPage.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" @click="saveRule">{{ t('billingPage.save') }}</el-button>
      </template>
    </el-dialog>

    <!-- 充值弹窗 -->
    <el-dialog v-model="rechargeVisible" :title="t('billingPage.rechargeTitle')" width="440px">
      <el-form label-width="80px">
        <el-form-item :label="t('billingPage.amount')" required>
          <el-input-number v-model="rechargeForm.amount" :min="0.01" :precision="2" :step="10" />
          <span class="unit">{{ t('billingPage.yuan') }}</span>
        </el-form-item>
        <el-form-item :label="t('billingPage.note')">
          <el-input v-model="rechargeForm.note" :placeholder="t('billingPage.notePh')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rechargeVisible = false">{{ t('billingPage.cancel') }}</el-button>
        <el-button type="primary" :loading="recharging" @click="doRecharge">{{ t('billingPage.confirmRecharge') }}</el-button>
      </template>
    </el-dialog>

    <!-- 账单明细 -->
    <el-drawer v-model="billVisible" :title="`${t('billingPage.billDetail')}${bill?.period || ''}`" size="640px">
      <div v-if="bill" class="bill-summary">
        <span>{{ t('billingPage.calls') }} {{ bill.calls }}{{ t('billingPage.callsUnit') }}</span>
        <span>{{ t('billingPage.billTokens') }}{{ fmt(bill.totalTokens) }}</span>
        <span>{{ t('billingPage.billCost') }}{{ money(bill.cost) }}</span>
      </div>
      <el-table :data="billRows" size="small" max-height="520">
        <el-table-column :label="t('billingPage.time')" width="160">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column prop="source" :label="t('billingPage.source')" width="80" />
        <el-table-column prop="modelName" :label="t('billingPage.model')" width="130" />
        <el-table-column prop="inputTokens" :label="t('billingPage.inputTokens')" width="90" />
        <el-table-column prop="outputTokens" :label="t('billingPage.outputTokens')" width="90" />
        <el-table-column :label="t('billingPage.cost')" width="100">
          <template #default="{ row }">¥{{ money(row.cost) }}</template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <!-- 配额调整 -->
    <el-dialog v-model="quotaVisible" :title="t('billingPage.quotaTitle')" width="520px">
      <el-form label-width="130px">
        <el-form-item :label="t('billingPage.agentLimit')"><el-input-number v-model="quotaForm.maxAgents" :min="0" /></el-form-item>
        <el-form-item :label="t('billingPage.kbLimit')"><el-input-number v-model="quotaForm.maxKnowledgeBases" :min="0" /></el-form-item>
        <el-form-item :label="t('billingPage.dailyTokenLimit')"><el-input-number v-model="quotaForm.maxTokensPerDay" :min="0" :step="100000" /></el-form-item>
        <el-form-item :label="t('billingPage.storageLimitBytes')"><el-input-number v-model="quotaForm.maxStorageBytes" :min="0" :step="1073741824" /></el-form-item>
        <el-form-item :label="t('billingPage.apiRate')"><el-input-number v-model="quotaForm.maxApiCallsPerMin" :min="0" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="quotaVisible = false">{{ t('billingPage.cancel') }}</el-button>
        <el-button type="primary" :loading="savingQuota" @click="saveQuota">{{ t('billingPage.save') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'
import { useUserStore } from '../stores/user'

const { t } = useI18n()

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
    { label: t('billingPage.kbQuota'), used: u.knowledgeBases || 0, max: q.maxKnowledgeBases || 0 },
    { label: t('billingPage.todayQuota'), used: u.tokensToday || 0, max: q.maxTokensPerDay || 0 },
    { label: t('billingPage.storage') + ' (MB)', used: Math.round((u.storageBytes || 0) / 1048576), max: Math.round((q.maxStorageBytes || 0) / 1048576) }
  ]
  return items.map(it => ({
    ...it,
    percent: it.max > 0 ? Math.min(100, Math.round(it.used * 100 / it.max)) : 0,
    text: it.max > 0 ? `${it.used} / ${it.max}` : `${it.used} / ${t('billingPage.unlimited')}`
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
  return v ? v : t('billingPage.unlimited')
}

function mb(v?: number) {
  const n = Number(v || 0)
  if (n <= 0) return t('billingPage.unlimited')
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
    ElMessage.success(t('billingPage.saved'))
    ruleVisible.value = false
    rules.value = (await http.get('/billing/rules') as any)?.list || []
  } finally {
    saving.value = false
  }
}

async function removeRule(row: any) {
  await ElMessageBox.confirm(`${t('billingPage.deleteRulePrefix')}${row.modelName || t('billingPage.defaultRule')}${t('billingPage.deleteRuleMid')}`,
    t('billingPage.deleteTitle'), { type: 'warning', confirmButtonText: t('billingPage.delete'), cancelButtonText: t('billingPage.cancel') })
  await http.delete(`/billing/rules/${row.id}`)
  ElMessage.success(t('billingPage.deleted'))
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
    ElMessage.success(t('billingPage.rechargeSuccess'))
    transactions.value = ((await http.get('/billing/transactions', { params: { pageSize: 50 } })) as any)?.list || []
  } finally {
    recharging.value = false
  }
}

async function generateBill() {
  if (!billPeriod.value) { ElMessage.warning(t('billingPage.selectPeriod')); return }
  generating.value = true
  try {
    const res = await http.post('/billing/bills/generate', { period: billPeriod.value }) as any
    ElMessage.success(`${t('billingPage.generatedBill')}${res.period}${t('billingPage.generatedBillMid')}${money(res.cost)}`)
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
    ElMessage.success(t('billingPage.quotaUpdated'))
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