<template>
  <div class="page">
    <div class="toolbar">
      <span class="page-title">租户设置</span>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </div>

    <el-card shadow="never" class="mb">
      <template #header>基本信息</template>
      <el-form label-width="120px" style="max-width: 520px">
        <el-form-item label="租户标识"><el-input :model-value="info.tenantKey" disabled /></el-form-item>
        <el-form-item label="名称"><el-input v-model="info.name" /></el-form-item>
        <el-form-item label="状态"><el-input :model-value="info.status" disabled /></el-form-item>
        <el-form-item label="创建时间"><el-input :model-value="fmt(info.createdAt)" disabled /></el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="mb">
      <template #header>配额</template>
      <el-descriptions :column="3" border>
        <el-descriptions-item label="Agent 上限">{{ info.quota?.maxAgents ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="知识库上限">{{ info.quota?.maxKnowledgeBases ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="每日 Token">{{ info.quota?.maxTokensPerDay ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="存储上限">{{ fmtBytes(info.quota?.maxStorageBytes) }}</el-descriptions-item>
        <el-descriptions-item label="API 限流/分">{{ info.quota?.maxApiCallsPerMin ?? '-' }}</el-descriptions-item>
      </el-descriptions>
      <div class="hint">配额由平台管理员调整（配额变更流程见需求 3.8）</div>
    </el-card>

    <el-card shadow="never" class="mb">
      <template #header>单点登录（SSO / OIDC）</template>
      <el-form label-width="140px" style="max-width: 680px">
        <el-form-item label="启用 SSO"><el-switch v-model="sso.enabled" /></el-form-item>
        <el-form-item label="按钮名称"><el-input v-model="sso.buttonName" /></el-form-item>
        <el-form-item label="Issuer">
          <el-input v-model="sso.issuer" placeholder="https://idp.example.com（OIDC 发现根地址）" />
        </el-form-item>
        <el-form-item label="Client ID"><el-input v-model="sso.clientId" /></el-form-item>
        <el-form-item label="Client Secret">
          <el-input v-model="sso.clientSecret" type="password" show-password
                    :placeholder="sso.clientSecretSet ? '已配置（留空或 ****** 不修改）' : '请输入 Client Secret'" />
        </el-form-item>
        <el-form-item label="Scopes"><el-input v-model="sso.scopes" /></el-form-item>
        <el-form-item label="回调地址"><el-input v-model="sso.redirectUri" /></el-form-item>
        <el-form-item label="自动开通用户"><el-switch v-model="sso.autoProvision" /></el-form-item>
        <el-form-item label="默认角色">
          <el-select v-model="sso.defaultRole" style="width: 160px">
            <el-option label="普通成员" value="MEMBER" />
            <el-option label="编辑者" value="EDITOR" />
          </el-select>
        </el-form-item>
        <el-button type="primary" :loading="ssoSaving" @click="saveSso">保存 SSO 配置</el-button>
      </el-form>
      <div class="hint">Client Secret 加密存储；留空或填 ****** 表示不修改。启用后登录页可使用「企业 SSO 登录」。</div>
    </el-card>

    <el-card shadow="never">
      <template #header>租户配置（config_key → 值）</template>
      <el-table :data="configRows" size="small">
        <el-table-column prop="key" label="配置项" width="220" />
        <el-table-column label="值">
          <template #default="{ row }">
            <el-input v-model="row.value" size="small" />
          </template>
        </el-table-column>
        <el-table-column width="70">
          <template #default="{ $index }">
            <el-button size="small" text type="danger" @click="configRows.splice($index, 1)">删</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="add-row">
        <el-input v-model="newKey" placeholder="新配置项 key，如 defaultModel" size="small" style="width: 220px" />
        <el-input v-model="newValue" placeholder="值" size="small" style="width: 260px" />
        <el-button size="small" @click="addConfig">添加</el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api/http'

const info = reactive<any>({ tenantKey: '', name: '', status: '', createdAt: '', quota: {}, config: {} })
const configRows = ref<{ key: string; value: string }[]>([])
const newKey = ref('')
const newValue = ref('')
const saving = ref(false)
const ssoSaving = ref(false)
const sso = reactive<any>({
  enabled: false, buttonName: '企业 SSO 登录', issuer: '', clientId: '', clientSecret: '',
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
})

async function saveSso() {
  ssoSaving.value = true
  try {
    await http.put('/auth/sso/settings', {
      enabled: sso.enabled, buttonName: sso.buttonName, issuer: sso.issuer,
      clientId: sso.clientId, clientSecret: sso.clientSecret, scopes: sso.scopes,
      redirectUri: sso.redirectUri, autoProvision: sso.autoProvision,
      defaultRole: sso.defaultRole
    })
    ElMessage.success('SSO 配置已保存')
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
    ElMessage.success('已保存')
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
</style>
