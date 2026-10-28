<template>
  <div class="page">
    <div class="toolbar">
      <div>
        <span class="page-title">{{ t('alertAck.title') }}</span>
        <el-tag class="ml8" size="small" :type="shared ? 'success' : 'warning'">
          {{ shared ? t('alertAck.shared') : t('alertAck.local') }}
        </el-tag>
      </div>
      <el-button size="small" :loading="loading" @click="load">{{ t('alertAck.refresh') }}</el-button>
    </div>

    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="mb" />

    <el-card shadow="never" class="mb">
      <template #header>{{ t('alertAck.pending') }}（{{ pending.length }}）</template>
      <el-table :data="pending" size="small" empty-text="">
        <el-table-column :label="t('alertAck.alert')" min-width="220">
          <template #default="{ row }">
            <div class="alert-name">{{ row.alert?.labels?.alertname || '-' }}</div>
            <div class="muted">{{ row.alert?.annotations?.summary || row.alert?.annotations?.description || '' }}</div>
          </template>
        </el-table-column>
        <el-table-column :label="t('alertAck.severity')" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.alert?.labels?.severity === 'critical' ? 'danger' : 'warning'">
              {{ row.alert?.labels?.severity || '-' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="fingerprint" :label="t('alertAck.fingerprint')" width="220" show-overflow-tooltip />
        <el-table-column :label="t('alertAck.dueAt')" width="170">
          <template #default="{ row }">{{ fmt(row.dueAt) }}</template>
        </el-table-column>
        <el-table-column :label="t('alertAck.channels')" width="150">
          <template #default="{ row }">{{ (row.channels || []).join(', ') }}</template>
        </el-table-column>
        <el-table-column :label="t('alertAck.ack')" width="90" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link :loading="acking === row.fingerprint" @click="ack(row)">
              {{ t('alertAck.ack') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="!pending.length && !loading" class="empty">{{ t('alertAck.empty') }}</div>
    </el-card>

    <el-card shadow="never">
      <template #header>{{ t('alertAck.acked') }}（{{ acked.length }}）</template>
      <el-table :data="acked" size="small" empty-text="">
        <el-table-column prop="fingerprint" :label="t('alertAck.fingerprint')" min-width="260" show-overflow-tooltip />
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'

const { t } = useI18n()
const pending = ref<any[]>([])
const acked = ref<any[]>([])
const shared = ref(false)
const loading = ref(false)
const acking = ref('')
const error = ref('')

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await http.get('/platform/alert-relay/state') as any
    pending.value = data.pending || []
    acked.value = (data.acked || []).map((fingerprint: string) => ({ fingerprint }))
    shared.value = Boolean(data.sharedState)
  } catch (e: any) {
    error.value = e?.code === 503 ? t('alertAck.relayDown') : (e?.message || t('alertAck.relayDown'))
  } finally {
    loading.value = false
  }
}

async function ack(row: any) {
  await ElMessageBox.confirm(t('alertAck.ackConfirm'), t('alertAck.title'), {
    type: 'warning', confirmButtonText: t('alertAck.ack'), cancelButtonText: t('common.cancel')
  })
  acking.value = row.fingerprint
  try {
    await http.post('/platform/alert-relay/ack', { fingerprint: row.fingerprint })
    ElMessage.success(t('alertAck.ackedMsg'))
    await load()
  } finally {
    acking.value = ''
  }
}

function fmt(ms?: number) {
  if (!ms) return '-'
  return new Date(ms).toLocaleString()
}
</script>

<style scoped>
.page { max-width: 1150px; margin: 0 auto; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; }
.page-title { font-size: 16px; font-weight: 600; }
.ml8 { margin-left: 8px; }
.mb { margin-bottom: 12px; }
.alert-name { font-weight: 600; }
.muted { color: #909399; font-size: 12px; margin-top: 2px; }
.empty { color: #909399; text-align: center; padding: 24px; }
</style>
