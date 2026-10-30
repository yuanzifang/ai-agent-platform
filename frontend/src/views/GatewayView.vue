<template>
  <div class="page">
    <div class="toolbar">
      <span class="title">{{ t('page.gateway') }}</span>
      <span class="sub">{{ t('gatewayPage.sub') }}</span>
      <div class="spacer" />
      <el-button @click="load">{{ t('common.refresh') }}</el-button>
      <el-button type="primary" @click="openCreate">{{ t('gatewayPage.create') }}</el-button>
    </div>

    <el-table :data="rows" size="small" v-loading="loading">
      <el-table-column prop="name" :label="t('gatewayPage.name')" min-width="140" />
      <el-table-column prop="targetUrl" :label="t('gatewayPage.target')" min-width="220" show-overflow-tooltip />
      <el-table-column :label="t('gatewayPage.routeKey')" width="170">
        <template #default="{ row }"><code>{{ row.routeKey }}</code></template>
      </el-table-column>
      <el-table-column :label="t('gatewayPage.rate')" width="90">
        <template #default="{ row }">{{ row.rateLimitPerMin || '∞' }}</template>
      </el-table-column>
      <el-table-column :label="t('gatewayPage.timeout')" width="100">
        <template #default="{ row }">{{ row.timeoutMs }}</template>
      </el-table-column>
      <el-table-column :label="t('gatewayPage.enabled')" width="90">
        <template #default="{ row }">
          <el-switch :model-value="row.enabled" @change="(v: boolean) => toggle(row, v)" />
        </template>
      </el-table-column>
      <el-table-column :label="t('gatewayPage.actions')" width="320" fixed="right">
        <template #default="{ row }">
          <el-button size="small" text @click="openKeys(row)">{{ t('gatewayPage.keys') }}</el-button>
          <el-button size="small" text @click="openLogs(row)">{{ t('gatewayPage.logs') }}</el-button>
          <el-button size="small" text @click="openHealth(row)">{{ t('gatewayPage.health') }}</el-button>
          <el-button size="small" text @click="openEdit(row)">{{ t('gatewayPage.edit') }}</el-button>
          <el-button size="small" text type="warning" @click="rotate(row)">{{ t('gatewayPage.rotate') }}</el-button>
          <el-button size="small" text type="danger" @click="remove(row)">{{ t('gatewayPage.delete') }}</el-button>
        </template>
      </el-table-column>
      <template #empty><el-empty :description="t('gatewayPage.empty')" :image-size="60" /></template>
    </el-table>

    <el-dialog v-model="formVisible" :title="form.id ? t('gatewayPage.edit') : t('gatewayPage.createTitle')" width="560px">
      <el-form label-width="120px">
        <el-form-item :label="t('gatewayPage.name')" required>
          <el-input v-model="form.name" :placeholder="t('gatewayPage.namePh')" maxlength="128" />
        </el-form-item>
        <el-form-item :label="t('gatewayPage.target')" required>
          <div class="url-weight-row">
            <el-input v-model="form.targetUrl" :placeholder="t('gatewayPage.targetPh')" maxlength="512" />
            <el-input-number v-model="form.targetWeight" :min="0" :max="1000" />
          </div>
        </el-form-item>
        <el-form-item :label="t('gatewayPage.fallback')">
          <div class="url-weight-row">
            <el-input v-model="form.fallbackUrl" :placeholder="t('gatewayPage.fallbackPh')" maxlength="512" />
            <el-input-number v-model="form.fallbackWeight" :min="0" :max="1000" />
          </div>
        </el-form-item>
        <el-form-item :label="t('gatewayPage.extraUpstreams')">
          <el-input v-model="form.upstreamUrlsText" type="textarea" :rows="3"
                    :placeholder="t('gatewayPage.extraUpstreamsPh')" />
          <div class="hint mt4">{{ t('gatewayPage.weightHint') }}</div>
        </el-form-item>
        <el-form-item :label="t('gatewayPage.timeoutLabel')">
          <el-input-number v-model="form.timeoutMs" :min="1000" :max="120000" :step="1000" />
        </el-form-item>
        <el-form-item :label="t('gatewayPage.retryLabel')">
          <el-input-number v-model="form.retries" :min="0" :max="3" />
        </el-form-item>
        <el-form-item :label="t('gatewayPage.rateLabel')">
          <el-input-number v-model="form.rateLimitPerMin" :min="0" :max="100000" />
        </el-form-item>
        <el-form-item :label="t('gatewayPage.healthCheck')">
          <el-switch v-model="form.healthCheckEnabled" />
          <span class="hint ml8">{{ t('gatewayPage.healthCheckHint') }}</span>
        </el-form-item>
        <el-form-item v-if="form.healthCheckEnabled" :label="t('gatewayPage.healthPath')">
          <el-input v-model="form.healthPath" :placeholder="t('gatewayPage.healthPathPh')" maxlength="512" />
        </el-form-item>
        <el-form-item :label="t('gatewayPage.wsMaxConnections')">
          <el-input-number v-model="form.wsMaxConnections" :min="0" :max="10000" />
          <span class="hint ml8">{{ t('gatewayPage.wsMaxConnectionsHint') }}</span>
        </el-form-item>
        <el-form-item :label="t('gatewayPage.signingEnabled')">
          <el-switch v-model="form.signingEnabled" />
          <span class="hint ml8">{{ t('gatewayPage.signingHint') }}</span>
        </el-form-item>
        <el-form-item :label="t('gatewayPage.requestSchema')">
          <el-input v-model="form.requestSchema" type="textarea" :rows="4"
                    :placeholder="t('gatewayPage.requestSchemaPh')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">{{ t('gatewayPage.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ t('gatewayPage.save') }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="secretVisible" :title="t('gatewayPage.secretTitle')" width="620px" :close-on-click-modal="false">
      <el-form label-width="100px">
        <el-form-item v-if="endpoint" :label="t('gatewayPage.endpoint')"><code>{{ endpoint }}</code></el-form-item>
        <el-form-item v-if="secretKey" :label="t('gatewayPage.apiKey')"><code>{{ secretKey }}</code></el-form-item>
        <el-form-item v-if="signingSecret" :label="t('gatewayPage.signingSecret')">
          <code>{{ signingSecret }}</code>
        </el-form-item>
      </el-form>
      <el-alert type="warning" :closable="false" :title="t('gatewayPage.secretHint')" />
      <template #footer>
        <el-button type="primary" @click="copyKey">{{ t('gatewayPage.copy') }}</el-button>
        <el-button @click="secretVisible = false">{{ t('gatewayPage.close') }}</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="logsVisible" :title="`${t('gatewayPage.logsTitle')}${logsName}`" size="720px">
      <el-table :data="logs" size="small" max-height="620">
        <el-table-column prop="createdAt" :label="t('gatewayPage.time')" width="170" />
        <el-table-column prop="method" :label="t('gatewayPage.method')" width="80" />
        <el-table-column prop="path" :label="t('gatewayPage.path')" min-width="160" show-overflow-tooltip />
        <el-table-column prop="status" :label="t('gatewayPage.status')" width="80" />
        <el-table-column prop="upstreamStatus" :label="t('gatewayPage.upstream')" width="90" />
        <el-table-column prop="latencyMs" :label="t('gatewayPage.latency')" width="90" />
        <el-table-column prop="error" :label="t('gatewayPage.error')" min-width="140" show-overflow-tooltip />
        <template #empty><el-empty :description="t('gatewayPage.noLogs')" :image-size="50" /></template>
      </el-table>
    </el-drawer>

    <el-drawer v-model="keysVisible" :title="`${t('gatewayPage.keysTitle')}${keysRouteName}`" size="820px">
      <div class="key-create">
        <el-input v-model="newKeyName" :placeholder="t('gatewayPage.keyNamePh')" style="width: 180px" />
        <el-input-number v-model="newKeyRate" :min="0" :max="100000" :placeholder="t('gatewayPage.keyRateCol')" />
        <el-input-number v-model="newKeyDailyQuota" :min="0" :max="100000000" :placeholder="t('gatewayPage.keyDailyQuota')" />
        <el-input-number v-model="newKeyMaxBody" :min="0" :max="104857600" :placeholder="t('gatewayPage.keyMaxBody')" />
        <el-date-picker v-model="newKeyExpires" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss"
                        :placeholder="t('gatewayPage.keyExpires')" />
        <el-input v-model="newKeyMethods" :placeholder="t('gatewayPage.keyMethodsPh')" style="width: 150px" />
        <el-input v-model="newKeyPaths" :placeholder="t('gatewayPage.keyPathsPh')" style="width: 220px" />
        <el-input v-model="newKeyIps" :placeholder="t('gatewayPage.keyIpsPh')" style="width: 200px" />
        <el-button type="primary" :loading="keyCreating" @click="createKey">{{ t('gatewayPage.keyCreate') }}</el-button>
      </div>
      <el-alert v-if="newKeyPlain" type="success" :closable="true" @close="newKeyPlain = ''"
                :title="`${t('gatewayPage.keyCreated')} ${newKeyPlain}`" class="mb12" />
      <el-table :data="apiKeys" size="small" max-height="520">
        <el-table-column prop="name" :label="t('gatewayPage.keyName')" min-width="120" />
        <el-table-column prop="keyPrefix" :label="t('gatewayPage.keyPrefixCol')" width="130" />
        <el-table-column :label="t('gatewayPage.keyStatus')" width="90">
          <template #default="{ row }">
            <el-switch :model-value="row.enabled" @change="(v: boolean) => toggleKey(row, v)" />
          </template>
        </el-table-column>
        <el-table-column prop="rateLimitPerMin" :label="t('gatewayPage.keyRateCol')" width="90" />
        <el-table-column prop="dailyQuota" :label="t('gatewayPage.keyDailyQuota')" width="110" />
        <el-table-column prop="maxBodyBytes" :label="t('gatewayPage.keyMaxBody')" width="130" />
        <el-table-column prop="allowedMethods" :label="t('gatewayPage.keyMethods')" width="120" />
        <el-table-column prop="allowedPaths" :label="t('gatewayPage.keyPaths')" min-width="150" show-overflow-tooltip />
        <el-table-column prop="allowedIps" :label="t('gatewayPage.keyIps')" min-width="150" show-overflow-tooltip />
        <el-table-column prop="requestCount" :label="t('gatewayPage.keyCalls')" width="100" />
        <el-table-column prop="lastUsedAt" :label="t('gatewayPage.keyLastUsed')" width="170" />
        <el-table-column prop="expiresAt" :label="t('gatewayPage.keyExpires')" width="170" />
        <el-table-column :label="t('gatewayPage.actions')" width="80" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text type="danger" @click="removeKey(row)">{{ t('gatewayPage.delete') }}</el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty :description="t('gatewayPage.noKeys')" :image-size="50" /></template>
      </el-table>
    </el-drawer>

    <el-drawer v-model="healthVisible" :title="`${t('gatewayPage.healthTitle')}${healthRoute.name || ''}`" size="720px">
      <div class="health-toolbar">
        <span class="hint">{{ t('gatewayPage.healthHint') }}</span>
        <el-button type="primary" size="small" :loading="healthChecking" @click="runHealth">
          {{ t('gatewayPage.checkNow') }}
        </el-button>
      </div>
      <el-table :data="healthRows" size="small" max-height="620">
        <el-table-column prop="upstream" :label="t('gatewayPage.upstream')" min-width="240" show-overflow-tooltip />
        <el-table-column :label="t('gatewayPage.healthStatus')" width="110">
          <template #default="{ row }">
            <el-tag :type="row.status === 'UP' ? 'success' : row.status === 'DOWN' ? 'danger' : 'info'">
              {{ healthStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="statusCode" :label="t('gatewayPage.statusCode')" width="90" />
        <el-table-column prop="latencyMs" :label="t('gatewayPage.latency')" width="90" />
        <el-table-column prop="checkedAt" :label="t('gatewayPage.checkedAt')" width="170" />
        <el-table-column prop="error" :label="t('gatewayPage.error')" min-width="140" show-overflow-tooltip />
        <template #empty><el-empty :description="t('gatewayPage.noHealth')" :image-size="50" /></template>
      </el-table>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'

const { t } = useI18n()
const rows = ref<any[]>([])
const loading = ref(false)
const saving = ref(false)
const formVisible = ref(false)
const secretVisible = ref(false)
const secretKey = ref('')
const signingSecret = ref('')
const endpoint = ref('')
const logsVisible = ref(false)
const logs = ref<any[]>([])
const logsName = ref('')
const form = reactive<any>({ id: 0, name: '', targetUrl: '', fallbackUrl: '', upstreamUrlsText: '', timeoutMs: 15000, retries: 0, rateLimitPerMin: 60, healthCheckEnabled: true, healthPath: '/', wsMaxConnections: 100, targetWeight: 100, fallbackWeight: 0, signingEnabled: false, requestSchema: '' })
const currentRouteKey = ref('')

const endpointBase = computed(() => `${location.origin}/api/v1/gateway/call/${currentRouteKey.value}`)

onMounted(load)

async function load() {
  loading.value = true
  try {
    rows.value = (await http.get('/gateway/routes') as any) || []
  } finally { loading.value = false }
}

function openCreate() {
  Object.assign(form, { id: 0, name: '', targetUrl: '', fallbackUrl: '', upstreamUrlsText: '', timeoutMs: 15000, retries: 0, rateLimitPerMin: 60, healthCheckEnabled: true, healthPath: '/', wsMaxConnections: 100, targetWeight: 100, fallbackWeight: 0, signingEnabled: false, requestSchema: '' })
  formVisible.value = true
}

function openEdit(row: any) {
  Object.assign(form, {
    id: row.id, name: row.name, targetUrl: row.targetUrl, fallbackUrl: row.fallbackUrl || '',
    upstreamUrlsText: (row.upstreamUrls || []).map((u: string) => {
      const w = row.upstreamWeights?.[u]
      return w == null ? u : `${u}|${w}`
    }).join('\n'),
    timeoutMs: row.timeoutMs, retries: row.retries, rateLimitPerMin: row.rateLimitPerMin,
    healthCheckEnabled: row.healthCheckEnabled !== false, healthPath: row.healthPath || '/',
    wsMaxConnections: row.wsMaxConnections ?? 100,
    targetWeight: row.targetWeight ?? 100, fallbackWeight: row.fallbackWeight ?? 0,
    signingEnabled: row.signingEnabled === true,
    requestSchema: row.requestSchema || ''
  })
  formVisible.value = true
}

async function save() {
  if (!form.name.trim() || !form.targetUrl.trim()) {
    ElMessage.warning(t('gatewayPage.nameRequired')); return
  }
  saving.value = true
  try {
    const payload = { ...form }
    const extraUrls: string[] = []
    const extraWeights: Record<string, number> = {}
    for (const line of String(form.upstreamUrlsText || '').split(/\n|,/)) {
      const [rawUrl, rawWeight] = line.split('|')
      const url = (rawUrl || '').trim()
      if (!url) continue
      extraUrls.push(url)
      if (rawWeight != null && rawWeight.trim() !== '') {
        const weight = Number(rawWeight.trim())
        if (!Number.isFinite(weight) || weight < 0 || weight > 1000) {
          ElMessage.warning(t('gatewayPage.weightInvalid')); return
        }
        extraWeights[url] = weight
      }
    }
    payload.upstreamUrls = extraUrls
    payload.upstreamWeights = extraWeights
    delete payload.upstreamUrlsText
    const res = form.id
      ? await http.put(`/gateway/routes/${form.id}`, payload) as any
      : await http.post('/gateway/routes', payload) as any
    formVisible.value = false
    await load()
    if (res?.apiKey || res?.signingSecret) showSecret(res)
    ElMessage.success(t('gatewayPage.saved'))
  } finally { saving.value = false }
}

function showSecret(row: any) {
  currentRouteKey.value = row.routeKey || currentRouteKey.value
  secretKey.value = row.apiKey || ''
  signingSecret.value = row.signingSecret || ''
  endpoint.value = row.routeKey ? endpointBase.value : ''
  secretVisible.value = true
}

async function rotate(row: any) {
  const res = await http.post(`/gateway/routes/${row.id}/rotate-key`, {}) as any
  await load()
  showSecret(res)
  ElMessage.success(t('gatewayPage.keyRotated'))
}

async function toggle(row: any, enabled: boolean) {
  await http.put(`/gateway/routes/${row.id}`, { enabled })
  await load()
}

async function remove(row: any) {
  await ElMessageBox.confirm(t('gatewayPage.deleteConfirm'), t('gatewayPage.deleteTitle'), { type: 'warning' })
  await http.delete(`/gateway/routes/${row.id}`)
  ElMessage.success(t('gatewayPage.deleted'))
  await load()
}

// ---- 调用方 API Keys ----
const keysVisible = ref(false)
const keysRoute = ref<any>({})
const keysRouteName = computed(() => keysRoute.value.name || '')
const apiKeys = ref<any[]>([])
const newKeyName = ref('')
const newKeyRate = ref<number | undefined>(undefined)
const newKeyDailyQuota = ref<number | undefined>(undefined)
const newKeyMaxBody = ref<number | undefined>(undefined)
const newKeyExpires = ref<string>('')
const newKeyMethods = ref('')
const newKeyPaths = ref('')
const newKeyIps = ref('')
const keyCreating = ref(false)
const newKeyPlain = ref('')

async function openKeys(row: any) {
  keysRoute.value = row
  keysVisible.value = true
  apiKeys.value = await http.get(`/gateway/routes/${row.id}/keys`) as any
}

async function createKey() {
  keyCreating.value = true
  try {
    const res = await http.post(`/gateway/routes/${keysRoute.value.id}/keys`, {
      name: newKeyName.value || '调用方',
      rateLimitPerMin: newKeyRate.value ?? null,
      dailyQuota: newKeyDailyQuota.value ?? null,
      maxBodyBytes: newKeyMaxBody.value ?? null,
      expiresAt: newKeyExpires.value || null,
      allowedMethods: newKeyMethods.value || null,
      allowedPaths: newKeyPaths.value || null,
      allowedIps: newKeyIps.value || null
    }) as any
    newKeyPlain.value = res.apiKey || ''
    ElMessage.success(t('gatewayPage.keyCreated'))
    apiKeys.value = await http.get(`/gateway/routes/${keysRoute.value.id}/keys`) as any
    newKeyName.value = ''
    newKeyRate.value = undefined
    newKeyDailyQuota.value = undefined
    newKeyMaxBody.value = undefined
    newKeyExpires.value = ''
    newKeyMethods.value = ''
    newKeyPaths.value = ''
    newKeyIps.value = ''
  } finally {
    keyCreating.value = false
  }
}

async function toggleKey(row: any, v: boolean) {
  await http.put(`/gateway/routes/${keysRoute.value.id}/keys/${row.id}`, { enabled: v })
  ElMessage.success(v ? t('gatewayPage.keyEnabled') : t('gatewayPage.keyDisabled'))
  apiKeys.value = await http.get(`/gateway/routes/${keysRoute.value.id}/keys`) as any
}

async function removeKey(row: any) {
  await http.delete(`/gateway/routes/${keysRoute.value.id}/keys/${row.id}`)
  ElMessage.success(t('gatewayPage.keyDeleted'))
  apiKeys.value = await http.get(`/gateway/routes/${keysRoute.value.id}/keys`) as any
}

async function openLogs(row: any) {
  logsName.value = row.name
  logs.value = (await http.get(`/gateway/routes/${row.id}/logs`) as any) || []
  logsVisible.value = true
}

const healthVisible = ref(false)
const healthRoute = ref<any>({})
const healthRows = ref<any[]>([])
const healthChecking = ref(false)

async function openHealth(row: any) {
  healthRoute.value = row
  healthRows.value = (await http.get(`/gateway/routes/${row.id}/health`) as any) || []
  healthVisible.value = true
}

async function runHealth() {
  healthChecking.value = true
  try {
    healthRows.value = (await http.post(`/gateway/routes/${healthRoute.value.id}/health/check`, {}) as any) || []
    ElMessage.success(t('gatewayPage.healthChecked'))
  } finally {
    healthChecking.value = false
  }
}

function healthStatusText(status: string) {
  const map: Record<string, string> = {
    UP: t('gatewayPage.healthUp'), DOWN: t('gatewayPage.healthDown'),
    UNKNOWN: t('gatewayPage.healthUnknown'), DISABLED: t('gatewayPage.healthDisabled')
  }
  return map[status] || status || t('gatewayPage.healthUnknown')
}

async function copyKey() {
  const lines = []
  if (secretKey.value) lines.push(`X-Gateway-Key: ${secretKey.value}`)
  if (signingSecret.value) lines.push(`X-Gateway-Signing-Secret: ${signingSecret.value}`)
  if (endpoint.value) lines.push(endpoint.value)
  await navigator.clipboard.writeText(lines.join('\n'))
  ElMessage.success(t('gatewayPage.copied'))
}
</script>

<style scoped>
.page { background: #fff; border: 1px solid #e4e7ed; border-radius: 6px; padding: 14px; }
.toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.title { font-weight: 600; font-size: 15px; }
.sub, .hint { color: #909399; font-size: 12px; }
.spacer { flex: 1; }
.health-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.ml8 { margin-left: 8px; }
.url-weight-row { display: flex; align-items: center; gap: 8px; width: 100%; }
.url-weight-row .el-input-number { width: 120px; flex-shrink: 0; }
.key-create { display: flex; gap: 8px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.mb12 { margin-bottom: 12px; }
.mt4 { margin-top: 4px; }
</style>
