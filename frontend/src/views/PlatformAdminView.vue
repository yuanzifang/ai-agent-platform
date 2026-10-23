<template>
  <div class="page">
    <div class="toolbar">
      <span class="page-title">{{ t('page.platform') }}</span>
      <el-button type="primary" @click="createVisible = true">{{ t('platformPage.createTenant') }}</el-button>
    </div>

    <el-card shadow="never">
      <el-table :data="tenants" size="small">
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="tenantKey" :label="t('platformPage.tenantKey')" width="140" />
        <el-table-column prop="name" :label="t('platformPage.name')" width="180" />
        <el-table-column :label="t('platformPage.status')" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : row.status === 'SUSPENDED' ? 'danger' : 'info'">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="members" :label="t('platformPage.members')" width="80" />
        <el-table-column :label="t('platformPage.createdAt')" width="150">
          <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column :label="t('platformPage.actions')" min-width="160">
          <template #default="{ row }">
            <el-button v-if="row.status !== 'ACTIVE'" size="small" type="success" plain @click="setStatus(row, 'ACTIVE')">{{ t('platformPage.enable') }}</el-button>
            <el-button v-if="row.status === 'ACTIVE'" size="small" type="danger" plain @click="setStatus(row, 'SUSPENDED')">{{ t('platformPage.disable') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="createVisible" :title="t('platformPage.createTitle')" width="520px">
      <el-form label-width="110px">
        <el-form-item :label="t('platformPage.tenantKey')" required>
          <el-input v-model="form.tenantKey" :placeholder="t('platformPage.keyPh')" />
        </el-form-item>
        <el-form-item :label="t('platformPage.name')" required><el-input v-model="form.name" /></el-form-item>
        <el-form-item :label="t('platformPage.owner')" required>
          <el-input v-model="form.ownerUsername" :placeholder="t('platformPage.ownerPh')" />
        </el-form-item>
        <el-form-item :label="t('platformPage.password')" required>
          <el-input v-model="form.ownerPassword" type="password" show-password :placeholder="t('platformPage.passwordPh')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">{{ t('platformPage.cancel') }}</el-button>
        <el-button type="primary" :loading="creating" @click="create">{{ t('platformPage.create') }}</el-button>
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

const tenants = ref<any[]>([])
const createVisible = ref(false)
const creating = ref(false)
const form = reactive({ tenantKey: '', name: '', ownerUsername: '', ownerPassword: '' })

onMounted(load)

async function load() {
  tenants.value = await http.get('/platform/tenants') as any
}

async function create() {
  for (const k of ['tenantKey', 'name', 'ownerUsername', 'ownerPassword'] as const) {
    if (!form[k].trim()) { ElMessage.warning(t('platformPage.fillAll')); return }
  }
  creating.value = true
  try {
    await http.post('/platform/tenants', { ...form })
    ElMessage.success(t('platformPage.created'))
    createVisible.value = false
    Object.assign(form, { tenantKey: '', name: '', ownerUsername: '', ownerPassword: '' })
    await load()
  } finally {
    creating.value = false
  }
}

async function setStatus(row: any, status: string) {
  await ElMessageBox.confirm(`${t('platformPage.confirmPrefix')}${status === 'ACTIVE' ? t('platformPage.confirmEnable') : t('platformPage.confirmDisable')}${t('platformPage.confirmMid')}${row.name}${t('platformPage.confirmSuffix')}`, t('platformPage.confirmTitle'), { type: 'warning' })
  await http.patch(`/platform/tenants/${row.id}/status`, { status })
  ElMessage.success(t('platformPage.statusUpdated'))
  await load()
}

function fmt(t?: string) { return t ? String(t).replace('T', ' ').slice(0, 16) : '-' }
</script>

<style scoped>
.page { max-width: 1000px; margin: 0 auto; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; }
.page-title { font-size: 16px; font-weight: 600; }
</style>