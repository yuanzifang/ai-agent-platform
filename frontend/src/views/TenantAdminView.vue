<template>
  <div class="page">
    <div class="toolbar">
      <span class="page-title">{{ t('page.tenant') }}</span>
      <el-button type="primary" :loading="saving" @click="save">{{ t('tenantAdmin.save') }}</el-button>
    </div>

    <el-card shadow="never" class="mb">
      <template #header>{{ t('tenantAdmin.basic') }}</template>
      <el-form label-width="120px" style="max-width: 520px">
        <el-form-item :label="t('tenantAdmin.tenantKey')"><el-input :model-value="info.tenantKey" disabled /></el-form-item>
        <el-form-item :label="t('tenantAdmin.name')"><el-input v-model="info.name" /></el-form-item>
        <el-form-item :label="t('tenantAdmin.status')"><el-input :model-value="info.status" disabled /></el-form-item>
        <el-form-item :label="t('tenantAdmin.createdAt')"><el-input :model-value="fmt(info.createdAt)" disabled /></el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="mb">
      <template #header>{{ t('tenantAdmin.quota') }}</template>
      <el-descriptions :column="3" border>
        <el-descriptions-item :label="t('tenantAdmin.agentLimit')">{{ info.quota?.maxAgents ?? '-' }}</el-descriptions-item>
        <el-descriptions-item :label="t('tenantAdmin.kbLimit')">{{ info.quota?.maxKnowledgeBases ?? '-' }}</el-descriptions-item>
        <el-descriptions-item :label="t('tenantAdmin.dailyTokens')">{{ info.quota?.maxTokensPerDay ?? '-' }}</el-descriptions-item>
        <el-descriptions-item :label="t('tenantAdmin.storageLimit')">{{ fmtBytes(info.quota?.maxStorageBytes) }}</el-descriptions-item>
        <el-descriptions-item :label="t('tenantAdmin.apiRate')">{{ info.quota?.maxApiCallsPerMin ?? '-' }}</el-descriptions-item>
      </el-descriptions>
      <div class="hint">{{ t('tenantAdmin.quotaHint') }}</div>
    </el-card>

    <el-card shadow="never" class="mb">
      <template #header>{{ t('tenantAdmin.ownerTransfer') }}</template>
      <div class="owner-row">
        <el-select v-model="newOwnerId" :placeholder="t('tenantAdmin.selectOwner')" style="width: 280px">
          <el-option v-for="m in members" :key="m.id" :value="m.id"
                     :label="(m.displayName || m.username) + '（' + m.username + '）'"
                     :disabled="(m.roles || []).includes('OWNER')" />
        </el-select>
        <el-button type="warning" :loading="transferring" @click="transferOwner">{{ t('tenantAdmin.transfer') }}</el-button>
      </div>
      <div class="hint">{{ t('tenantAdmin.transferHint') }}</div>
    </el-card>

    <el-card shadow="never" class="mb">
      <template #header>{{ t('tenantAdmin.sso') }}</template>
      <el-form label-width="140px" style="max-width: 680px">
        <el-form-item :label="t('tenantAdmin.enableSso')"><el-switch v-model="sso.enabled" /></el-form-item>
        <el-form-item :label="t('tenantAdmin.buttonName')"><el-input v-model="sso.buttonName" /></el-form-item>
        <el-form-item label="Issuer">
          <el-input v-model="sso.issuer" :placeholder="t('tenantAdmin.issuerPh')" />
        </el-form-item>
        <el-form-item label="Client ID"><el-input v-model="sso.clientId" /></el-form-item>
        <el-form-item label="Client Secret">
          <el-input v-model="sso.clientSecret" type="password" show-password
                    :placeholder="sso.clientSecretSet ? t('tenantAdmin.secretSetPh') : t('tenantAdmin.secretPh')" />
        </el-form-item>
        <el-form-item label="Scopes"><el-input v-model="sso.scopes" /></el-form-item>
        <el-form-item :label="t('tenantAdmin.redirect')"><el-input v-model="sso.redirectUri" /></el-form-item>
        <el-form-item :label="t('tenantAdmin.autoProvision')"><el-switch v-model="sso.autoProvision" /></el-form-item>
        <el-form-item :label="t('tenantAdmin.defaultRole')">
          <el-select v-model="sso.defaultRole" style="width: 160px">
            <el-option :label="t('tenantAdmin.member')" value="MEMBER" />
            <el-option :label="t('tenantAdmin.editor')" value="EDITOR" />
          </el-select>
        </el-form-item>
        <el-button type="primary" :loading="ssoSaving" @click="saveSso">{{ t('tenantAdmin.saveSso') }}</el-button>
      </el-form>
      <div class="hint">{{ t('tenantAdmin.ssoHint') }}</div>
    </el-card>

    <el-card shadow="never">
      <template #header>{{ t('tenantAdmin.config') }}</template>
      <el-table :data="configRows" size="small">
        <el-table-column prop="key" :label="t('tenantAdmin.key')" width="220" />
        <el-table-column :label="t('tenantAdmin.value')">
          <template #default="{ row }">
            <el-input v-model="row.value" size="small" />
          </template>
        </el-table-column>
        <el-table-column width="70">
          <template #default="{ $index }">
            <el-button size="small" text type="danger" @click="configRows.splice($index, 1)">{{ t('tenantAdmin.remove') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="add-row">
        <el-input v-model="newKey" :placeholder="t('tenantAdmin.newKeyPh')" size="small" style="width: 220px" />
        <el-input v-model="newValue" :placeholder="t('tenantAdmin.valuePh')" size="small" style="width: 260px" />
        <el-button size="small" @click="addConfig">{{ t('tenantAdmin.add') }}</el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'

const { t } = useI18n()

const info = reactive<any>({ tenantKey: '', name: '', status: '', createdAt: '', quota: {}, config: {} })
const configRows = ref<{ key: string; value: string }[]>([])
const newKey = ref('')
const newValue = ref('')
const saving = ref(false)
const ssoSaving = ref(false)
const members = ref<any[]>([])
const newOwnerId = ref<number | null>(null)
const transferring = ref(false)
const sso = reactive<any>({
  enabled: false, buttonName: t('login.sso'), issuer: '', clientId: '', clientSecret: '',
  clientSecretSet: false, scopes: 'openid profile email', redirectUri: '',
  autoProvision: false, defaultRole: 'MEMBER'
})

onMounted(async () => {
  const data = await http.get('/tenant') as any
  Object.assign(info, data)
  configRows.value = Object.entries(data.config || {}).map(([key, value]) => ({ key, value: String(value) }))
  if (!configRows.value.some(r => r.key === 'defaultModel')) {
    newKey.value = 'defaultModel'
  }
  const s = await http.get('/auth/sso/settings') as any
  Object.assign(sso, s || {}, { clientSecret: '' })
  members.value = await http.get('/users') as any
})

async function transferOwner() {
  if (!newOwnerId.value) { ElMessage.warning(t('tenantAdmin.selectOwnerWarn')); return }
  const target = members.value.find(m => m.id === newOwnerId.value)
  await ElMessageBox.confirm(
    `${t('tenantAdmin.transferConfirmPrefix')}${target?.displayName || target?.username || newOwnerId.value}${t('tenantAdmin.transferConfirmMid')}`,
    t('tenantAdmin.transferTitle'), { type: 'warning', confirmButtonText: t('tenantAdmin.confirmTransfer'), cancelButtonText: t('userAdmin.cancel') })
  transferring.value = true
  try {
    await http.put('/tenant/owner', { userId: newOwnerId.value })
    ElMessage.success(t('tenantAdmin.transferred'))
    newOwnerId.value = null
    members.value = await http.get('/users') as any
  } finally {
    transferring.value = false
  }
}

async function saveSso() {
  ssoSaving.value = true
  try {
    await http.put('/auth/sso/settings', {
      enabled: sso.enabled, buttonName: sso.buttonName, issuer: sso.issuer,
      clientId: sso.clientId, clientSecret: sso.clientSecret, scopes: sso.scopes,
      redirectUri: sso.redirectUri, autoProvision: sso.autoProvision,
      defaultRole: sso.defaultRole
    })
    ElMessage.success(t('tenantAdmin.ssoSaved'))
  } finally {
    ssoSaving.value = false
  }
}

function addConfig() {
  if (!newKey.value.trim()) return
  configRows.value.push({ key: newKey.value.trim(), value: newValue.value })
  newKey.value = ''
  newValue.value = ''
}

async function save() {
  saving.value = true
  try {
    const config: Record<string, string> = {}
    for (const r of configRows.value) config[r.key] = r.value
    await http.put('/tenant', { name: info.name, config })
    ElMessage.success(t('tenantAdmin.saved'))
  } finally {
    saving.value = false
  }
}

function fmt(t?: string) { return t ? String(t).replace('T', ' ').slice(0, 19) : '-' }
function fmtBytes(v?: number) {
  if (!v) return '-'
  return (v / 1024 / 1024 / 1024).toFixed(1) + ' GB'
}
</script>

<style scoped>
.page { max-width: 1000px; margin: 0 auto; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; }
.page-title { font-size: 16px; font-weight: 600; }
.mb { margin-bottom: 14px; }
.hint { color: #909399; font-size: 12px; margin-top: 10px; }
.add-row { display: flex; gap: 8px; margin-top: 10px; }
.owner-row { display: flex; gap: 10px; align-items: center; }
</style>