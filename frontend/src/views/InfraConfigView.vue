<template>
  <div class="page">
    <div class="toolbar">
      <div>
        <span class="page-title">{{ t('infraPage.title') }}</span>
        <div class="sub">{{ t('infraPage.sub') }}</div>
      </div>
      <div>
        <el-button @click="load">{{ t('infraPage.reload') }}</el-button>
        <el-button :loading="testing" @click="testConnections">{{ t('infraPage.test') }}</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ t('infraPage.save') }}</el-button>
      </div>
    </div>

    <el-card v-if="testResults" shadow="never" class="section">
      <template #header>{{ t('infraPage.testResults') }}</template>
      <el-table :data="testRows" size="small">
        <el-table-column prop="name" :label="t('infraPage.component')" width="140" />
        <el-table-column :label="t('infraPage.result')" width="100">
          <template #default="{ row }"><el-tag :type="row.ok ? 'success' : 'danger'" size="small">{{ row.ok ? 'OK' : 'FAIL' }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="message" :label="t('infraPage.message')" />
      </el-table>
    </el-card>

    <el-card v-if="outbox" shadow="never" class="section">
      <template #header>{{ t('infraPage.outboxTitle') }}</template>
      <div class="outbox-row">
        <span>{{ t('infraPage.outboxPending') }} <b>{{ outbox.pending ?? 0 }}</b></span>
        <span>{{ t('infraPage.outboxFailed') }} <b>{{ outbox.failed ?? 0 }}</b></span>
        <span>{{ t('infraPage.outboxLocked') }} <b>{{ outbox.locked ?? 0 }}</b></span>
        <span>{{ t('infraPage.outboxOldest') }} <b>{{ outbox.oldestPendingAt || '-' }}</b></span>
        <el-button v-if="(outbox.failed ?? 0) > 0" size="small" type="warning" plain
                   :loading="retryingOutbox" @click="retryOutboxFailed">
          {{ t('infraPage.outboxRetryFailed') }}
        </el-button>
      </div>
    </el-card>

    <el-alert type="warning" :closable="false" show-icon class="mb">
      {{ t('infraPage.restartHint') }}
      <div class="path">{{ t('infraPage.configFile') }}: {{ configFile }}</div>
    </el-alert>

    <el-card shadow="never" class="section">
      <template #header>{{ t('infraPage.mysql') }}</template>
      <el-form label-width="150px">
        <el-form-item label="JDBC URL"><el-input v-model="form.mysqlUrl" /></el-form-item>
        <el-form-item label="Username"><el-input v-model="form.mysqlUsername" /></el-form-item>
        <el-form-item label="Password">
          <el-input v-model="form.mysqlPassword" type="password" show-password
                    :placeholder="maskHint.mysqlPassword" />
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="section">
      <template #header>{{ t('infraPage.redis') }}</template>
      <el-form label-width="150px">
        <el-form-item label="Host"><el-input v-model="form.redisHost" /></el-form-item>
        <el-form-item label="Port"><el-input v-model="form.redisPort" /></el-form-item>
        <el-form-item label="Password">
          <el-input v-model="form.redisPassword" type="password" show-password
                    :placeholder="maskHint.redisPassword" />
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="section">
      <template #header>{{ t('infraPage.pg') }}</template>
      <el-form label-width="150px">
        <el-form-item label="JDBC URL"><el-input v-model="form.pgUrl" /></el-form-item>
        <el-form-item label="Username"><el-input v-model="form.pgUsername" /></el-form-item>
        <el-form-item label="Password">
          <el-input v-model="form.pgPassword" type="password" show-password
                    :placeholder="maskHint.pgPassword" />
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="section">
      <template #header>{{ t('infraPage.minio') }}</template>
      <el-form label-width="150px">
        <el-form-item label="Endpoint"><el-input v-model="form.minioEndpoint" /></el-form-item>
        <el-form-item label="Access Key"><el-input v-model="form.minioAccessKey" /></el-form-item>
        <el-form-item label="Secret Key">
          <el-input v-model="form.minioSecret" type="password" show-password
                    :placeholder="maskHint.minioSecret" />
        </el-form-item>
        <el-form-item label="Bucket"><el-input v-model="form.minioBucket" /></el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="section">
      <template #header>{{ t('infraPage.es') }}</template>
      <el-form label-width="150px">
        <el-form-item label="Enabled"><el-switch v-model="form.esEnabled" /></el-form-item>
        <el-form-item label="URL"><el-input v-model="form.esUrl" /></el-form-item>
        <el-form-item label="Index"><el-input v-model="form.esIndex" /></el-form-item>
        <el-form-item label="Alias"><el-input v-model="form.esAlias" /></el-form-item>
        <el-form-item label="Username"><el-input v-model="form.esUsername" /></el-form-item>
        <el-form-item label="Password">
          <el-input v-model="form.esPassword" type="password" show-password
                    :placeholder="maskHint.esPassword" />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="form.esApiKey" type="password" show-password
                    :placeholder="maskHint.esApiKey" />
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="section">
      <template #header>{{ t('infraPage.qdrant') }}</template>
      <el-form label-width="150px">
        <el-form-item label="Enabled"><el-switch v-model="form.qdrantEnabled" /></el-form-item>
        <el-form-item label="URL"><el-input v-model="form.qdrantUrl" /></el-form-item>
        <el-form-item label="Collection Prefix"><el-input v-model="form.qdrantCollectionPrefix" /></el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="form.qdrantApiKey" type="password" show-password
                    :placeholder="maskHint.qdrantApiKey" />
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="section">
      <template #header>
        <div class="card-head">
          <span>{{ t('infraPage.profileTitle') }}</span>
          <el-button size="small" type="primary" @click="openProfile()">{{ t('infraPage.addProfile') }}</el-button>
        </div>
      </template>
      <el-table :data="profiles" size="small">
        <el-table-column prop="name" :label="t('infraPage.profileName')" width="180" />
        <el-table-column prop="searchBackend" label="Search" width="100" />
        <el-table-column prop="vectorBackend" label="Vector" width="110" />
        <el-table-column prop="status" :label="t('infraPage.status')" width="100" />
        <el-table-column prop="description" :label="t('infraPage.description')" min-width="180" />
        <el-table-column :label="t('infraPage.actions')" width="140">
          <template #default="{ row }">
            <el-button size="small" text @click="openProfile(row)">{{ t('infraPage.edit') }}</el-button>
            <el-button size="small" text type="danger" @click="removeProfile(row)">{{ t('infraPage.delete') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" class="section">
      <template #header>
        <div class="card-head">
          <span>{{ t('infraPage.deployProfileTitle') }}</span>
          <el-button size="small" type="primary" @click="openDeployProfile()">
            {{ t('infraPage.addDeployProfile') }}
          </el-button>
        </div>
      </template>
      <el-table :data="deployProfiles" size="small">
        <el-table-column prop="name" :label="t('infraPage.profileName')" width="160" />
        <el-table-column prop="cpuLimit" label="CPU" width="80" />
        <el-table-column prop="memoryLimit" label="Memory" width="100" />
        <el-table-column prop="pidsLimit" label="PID" width="80" />
        <el-table-column :label="t('infraPage.deployReplicas')" width="110">
          <template #default="{ row }">{{ row.defaultReplicas }}-{{ row.maxReplicas }}</template>
        </el-table-column>
        <el-table-column prop="status" :label="t('infraPage.status')" width="100" />
        <el-table-column prop="description" :label="t('infraPage.description')" min-width="150" />
        <el-table-column :label="t('infraPage.actions')" width="140">
          <template #default="{ row }">
            <el-button size="small" text @click="openDeployProfile(row)">{{ t('infraPage.edit') }}</el-button>
            <el-button size="small" text type="danger" @click="removeDeployProfile(row)">{{ t('infraPage.delete') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="profileVisible" :title="profileForm.id ? t('infraPage.editProfile') : t('infraPage.addProfile')" width="520px">
      <el-form label-width="130px">
        <el-form-item :label="t('infraPage.profileName')" required><el-input v-model="profileForm.name" /></el-form-item>
        <el-form-item :label="t('infraPage.description')"><el-input v-model="profileForm.description" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="Search"><el-select v-model="profileForm.searchBackend" style="width: 220px">
          <el-option label="MySQL" value="MYSQL" /><el-option label="Elasticsearch" value="ES" />
        </el-select></el-form-item>
        <el-form-item label="Vector"><el-select v-model="profileForm.vectorBackend" style="width: 220px">
          <el-option label="pgvector" value="PGVECTOR" /><el-option label="Qdrant" value="QDRANT" />
        </el-select></el-form-item>
        <el-form-item :label="t('infraPage.status')"><el-switch v-model="profileForm.enabled" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="profileVisible = false">{{ t('infraPage.cancel') }}</el-button>
        <el-button type="primary" :loading="profileSaving" @click="saveProfile">{{ t('infraPage.save') }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="deployProfileVisible"
               :title="deployProfileForm.id ? t('infraPage.editDeployProfile') : t('infraPage.addDeployProfile')"
               width="520px">
      <el-form label-width="140px">
        <el-form-item :label="t('infraPage.profileName')" required>
          <el-input v-model="deployProfileForm.name" />
        </el-form-item>
        <el-form-item :label="t('infraPage.description')">
          <el-input v-model="deployProfileForm.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="CPU limit">
          <el-input v-model="deployProfileForm.cpuLimit" placeholder="1" />
        </el-form-item>
        <el-form-item label="Memory limit">
          <el-input v-model="deployProfileForm.memoryLimit" placeholder="512m" />
        </el-form-item>
        <el-form-item label="PID limit">
          <el-input-number v-model="deployProfileForm.pidsLimit" :min="16" :max="4096" />
        </el-form-item>
        <el-form-item :label="t('infraPage.defaultReplicas')">
          <el-input-number v-model="deployProfileForm.defaultReplicas" :min="1" :max="deployProfileForm.maxReplicas" />
        </el-form-item>
        <el-form-item :label="t('infraPage.maxReplicas')">
          <el-input-number v-model="deployProfileForm.maxReplicas" :min="1" :max="5" />
        </el-form-item>
        <el-form-item :label="t('infraPage.status')">
          <el-switch v-model="deployProfileForm.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="deployProfileVisible = false">{{ t('infraPage.cancel') }}</el-button>
        <el-button type="primary" :loading="deployProfileSaving" @click="saveDeployProfile">
          {{ t('infraPage.save') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'

const { t } = useI18n()
const saving = ref(false)
const testing = ref(false)
const testResults = ref<any>(null)
const outbox = ref<any>(null)
const retryingOutbox = ref(false)
const configFile = ref('')
const testRows = reactive<any[]>([])
const maskHint = reactive({
  mysqlPassword: '', redisPassword: '', pgPassword: '', minioSecret: '',
  esPassword: '', esApiKey: '', qdrantApiKey: ''
})
const profiles = ref<any[]>([])
const profileVisible = ref(false)
const profileSaving = ref(false)
const profileForm = reactive<any>({ id: null, name: '', description: '', searchBackend: 'MYSQL', vectorBackend: 'PGVECTOR', enabled: true })
const deployProfiles = ref<any[]>([])
const deployProfileVisible = ref(false)
const deployProfileSaving = ref(false)
const deployProfileForm = reactive<any>({
  id: null, name: '', description: '', cpuLimit: '1', memoryLimit: '512m',
  pidsLimit: 256, defaultReplicas: 1, maxReplicas: 5, enabled: true
})
const form = reactive<any>({
  mysqlUrl: '', mysqlUsername: '', mysqlPassword: '',
  redisHost: '', redisPort: '6379', redisPassword: '',
  pgUrl: '', pgUsername: '', pgPassword: '',
  minioEndpoint: '', minioAccessKey: '', minioSecret: '', minioBucket: '',
  esEnabled: false, esUrl: '', esIndex: '', esAlias: '', esUsername: '', esPassword: '', esApiKey: '',
  qdrantEnabled: false, qdrantUrl: '', qdrantCollectionPrefix: 'aap', qdrantApiKey: ''
})

onMounted(async () => { await load(); await loadProfiles(); await loadDeployProfiles() })

async function load() {
  const data = await http.get('/platform/infrastructure') as any
  configFile.value = data.configFile || ''
  Object.assign(form, {
    mysqlUrl: data.mysqlUrl || '', mysqlUsername: data.mysqlUsername || '', mysqlPassword: '',
    redisHost: data.redisHost || '', redisPort: String(data.redisPort || '6379'), redisPassword: '',
    pgUrl: data.pgUrl || '', pgUsername: data.pgUsername || '', pgPassword: '',
    minioEndpoint: data.minioEndpoint || '', minioAccessKey: data.minioAccessKey || '',
    minioSecret: '', minioBucket: data.minioBucket || '',
    esEnabled: !!data.esEnabled, esUrl: data.esUrl || '', esIndex: data.esIndex || '',
    esAlias: data.esAlias || '', esUsername: data.esUsername || '', esPassword: '', esApiKey: '',
    qdrantEnabled: !!data.qdrantEnabled, qdrantUrl: data.qdrantUrl || '',
    qdrantCollectionPrefix: data.qdrantCollectionPrefix || 'aap', qdrantApiKey: ''
  })
  maskHint.mysqlPassword = data.mysqlPasswordSet ? t('infraPage.keepSecret') : ''
  maskHint.redisPassword = data.redisPasswordSet ? t('infraPage.keepSecret') : ''
  maskHint.pgPassword = data.pgPasswordSet ? t('infraPage.keepSecret') : ''
  maskHint.minioSecret = data.minioSecretSet ? t('infraPage.keepSecret') : ''
  maskHint.esPassword = data.esPasswordSet ? t('infraPage.keepSecret') : ''
  maskHint.esApiKey = data.esApiKeySet ? t('infraPage.keepSecret') : ''
  maskHint.qdrantApiKey = data.qdrantApiKeySet ? t('infraPage.keepSecret') : ''
}

async function loadProfiles() {
  try { profiles.value = await http.get('/platform/resource-profiles') as any || [] } catch { profiles.value = [] }
}
function openProfile(row?: any) {
  Object.assign(profileForm, row ? {
    id: row.id, name: row.name, description: row.description || '',
    searchBackend: row.searchBackend || 'MYSQL', vectorBackend: row.vectorBackend || 'PGVECTOR',
    enabled: row.status !== 'DISABLED'
  } : { id: null, name: '', description: '', searchBackend: 'MYSQL', vectorBackend: 'PGVECTOR', enabled: true })
  profileVisible.value = true
}
async function saveProfile() {
  if (!profileForm.name.trim()) { ElMessage.warning(t('infraPage.profileNameRequired')); return }
  profileSaving.value = true
  try {
    const body = { name: profileForm.name, description: profileForm.description,
      searchBackend: profileForm.searchBackend, vectorBackend: profileForm.vectorBackend,
      status: profileForm.enabled ? 'ENABLED' : 'DISABLED' }
    if (profileForm.id) await http.put(`/platform/resource-profiles/${profileForm.id}`, body)
    else await http.post('/platform/resource-profiles', body)
    ElMessage.success(t('infraPage.saved'))
    profileVisible.value = false
    await loadProfiles()
  } finally { profileSaving.value = false }
}
async function removeProfile(row: any) {
  await ElMessageBox.confirm(`${t('infraPage.deleteProfile')}${row.name}?`, t('infraPage.delete'), { type: 'warning' })
  await http.delete(`/platform/resource-profiles/${row.id}`)
  ElMessage.success(t('infraPage.deleted'))
  await loadProfiles()
}

async function loadDeployProfiles() {
  try {
    deployProfiles.value = await http.get('/platform/deploy-resource-profiles') as any || []
  } catch { deployProfiles.value = [] }
}

function openDeployProfile(row?: any) {
  Object.assign(deployProfileForm, row ? {
    id: row.id, name: row.name, description: row.description || '',
    cpuLimit: row.cpuLimit || '1', memoryLimit: row.memoryLimit || '512m',
    pidsLimit: row.pidsLimit || 256, defaultReplicas: row.defaultReplicas || 1,
    maxReplicas: row.maxReplicas || 5, enabled: row.status !== 'DISABLED'
  } : {
    id: null, name: '', description: '', cpuLimit: '1', memoryLimit: '512m',
    pidsLimit: 256, defaultReplicas: 1, maxReplicas: 5, enabled: true
  })
  deployProfileVisible.value = true
}

async function saveDeployProfile() {
  if (!deployProfileForm.name.trim()) {
    ElMessage.warning(t('infraPage.profileNameRequired'))
    return
  }
  deployProfileSaving.value = true
  try {
    const body = {
      name: deployProfileForm.name, description: deployProfileForm.description,
      cpuLimit: deployProfileForm.cpuLimit, memoryLimit: deployProfileForm.memoryLimit,
      pidsLimit: deployProfileForm.pidsLimit, defaultReplicas: deployProfileForm.defaultReplicas,
      maxReplicas: deployProfileForm.maxReplicas,
      status: deployProfileForm.enabled ? 'ENABLED' : 'DISABLED'
    }
    if (deployProfileForm.id) await http.put(`/platform/deploy-resource-profiles/${deployProfileForm.id}`, body)
    else await http.post('/platform/deploy-resource-profiles', body)
    ElMessage.success(t('infraPage.saved'))
    deployProfileVisible.value = false
    await loadDeployProfiles()
  } finally { deployProfileSaving.value = false }
}

async function removeDeployProfile(row: any) {
  await ElMessageBox.confirm(`${t('infraPage.deleteProfile')}${row.name}?`, t('infraPage.delete'), { type: 'warning' })
  await http.delete(`/platform/deploy-resource-profiles/${row.id}`)
  ElMessage.success(t('infraPage.deleted'))
  await loadDeployProfiles()
}

async function loadOutbox() {
  try { outbox.value = await http.get('/platform/infrastructure/search-outbox') as any } catch { outbox.value = null }
}

async function retryOutboxFailed() {
  retryingOutbox.value = true
  try {
    const data = await http.post('/platform/infrastructure/search-outbox/retry-failed') as any
    ElMessage.success(t('infraPage.outboxRetried', { count: data.retried ?? 0 }))
    await loadOutbox()
  } finally {
    retryingOutbox.value = false
  }
}

async function testConnections() {
  testing.value = true
  try {
    testResults.value = await http.post('/platform/infrastructure/test', { ...form }) as any
    testRows.splice(0, testRows.length, ...Object.entries(testResults.value).map(([name, v]: any) => ({
      name, ok: !!v.ok, message: v.message || ''
    })))
  } finally { testing.value = false }
}

async function save() {
  saving.value = true
  try {
    const data = await http.put('/platform/infrastructure', { ...form }) as any
    if (data.restartRequired) ElMessage.warning(t('infraPage.savedRestart'))
    else ElMessage.success(t('infraPage.saved'))
    await load()
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.page { max-width: 980px; margin: 0 auto; }
.toolbar { display: flex; justify-content: space-between; align-items: center; gap: 16px; margin-bottom: 14px; }
.page-title { font-size: 16px; font-weight: 600; }
.sub { color: #909399; font-size: 12px; margin-top: 4px; }
.mb { margin-bottom: 14px; }
.path { margin-top: 4px; font-family: monospace; font-size: 12px; }
.section { margin-bottom: 14px; }
.card-head { display: flex; align-items: center; justify-content: space-between; }
.outbox-row { display: flex; gap: 28px; color: #606266; }
</style>
