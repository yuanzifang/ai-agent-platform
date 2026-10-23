<template>
  <div class="page">
    <div class="toolbar">
      <span class="page-title">{{ t('page.audit') }}</span>
      <div class="right">
        <el-button v-if="tab === 'ops'" size="small" @click="verifyChain">{{ t('audit.verify') }}</el-button>
        <el-radio-group v-model="tab" @change="load" class="ml8">
          <el-radio-button value="ops">{{ t('audit.opsTab') }}</el-radio-button>
          <el-radio-button value="logins">{{ t('audit.loginTab') }}</el-radio-button>
        </el-radio-group>
      </div>
    </div>

    <el-card shadow="never">
      <template #header>
        {{ tab === 'ops' ? t('audit.opsDesc') : t('audit.loginDesc') }}
      </template>

      <el-table v-if="tab === 'ops'" :data="rows" size="small">
        <el-table-column prop="createdAt" :label="t('audit.time')" width="160">
          <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column prop="userId" :label="t('audit.operator')" width="80" />
        <el-table-column prop="opType" :label="t('audit.op')" width="170">
          <template #default="{ row }">
            <el-tag size="small">{{ row.opType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="objectType" :label="t('audit.objectType')" width="100" />
        <el-table-column prop="objectId" :label="t('audit.objectId')" width="90" />
        <el-table-column prop="opResult" :label="t('audit.result')" width="80" />
        <el-table-column :label="t('audit.detail')" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="detail">{{ row.detail || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="ip" label="IP" width="120" />
        <el-table-column :label="t('audit.chain')" min-width="180">
          <template #default="{ row }">
            <span class="hash">{{ (row.prevHash || '').slice(0, 24) }}…</span>
          </template>
        </el-table-column>
      </el-table>

      <el-table v-else :data="rows" size="small">
        <el-table-column prop="createdAt" :label="t('audit.time')" width="170">
          <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column prop="userId" :label="t('audit.userId')" width="90" />
        <el-table-column :label="t('audit.result')" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.result === 'SUCCESS' ? 'success' : 'danger'">
              {{ row.result }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="ip" label="IP" width="140" />
        <el-table-column prop="userAgent" label="User-Agent" min-width="240">
          <template #default="{ row }">
            <span class="ua">{{ (row.userAgent || '').slice(0, 60) }}</span>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination v-if="total > pageSize" v-model:current-page="page" :page-size="pageSize"
                     :total="total" layout="prev, pager, next" class="mt" @current-change="load" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { onMounted, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import http from '../api/http'

const { t } = useI18n()

const tab = ref('ops')
const rows = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 20

onMounted(load)

async function load() {
  const url = tab.value === 'ops' ? '/audit/operations' : '/audit/logins'
  const data = await http.get(url, { params: { page: page.value, pageSize } }) as any
  rows.value = data.list || []
  total.value = data.total || 0
}

async function verifyChain() {
  const res = await http.get('/audit/verify') as any
  const lines = [
    res.message,
    `${t('audit.totalRecords')}${res.total}${t('audit.checkedV2')}${res.checked}${t('audit.skippedV1')}${res.skippedLegacy}`,
    res.brokenAtId ? `${t('audit.brokenAt')}${res.brokenAtId} (${res.brokenAtTime || '-'})` : ''
  ].filter(Boolean).join('\n')
  await ElMessageBox.alert(lines, res.valid ? t('audit.pass') : t('audit.fail'), {
    type: res.valid ? 'success' : 'error',
    confirmButtonText: t('audit.know')
  })
}

function fmt(t?: string) { return t ? String(t).replace('T', ' ').slice(0, 19) : '-' }
</script>

<style scoped>
.page { max-width: 1150px; margin: 0 auto; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; }
.right { display: flex; align-items: center; }
.ml8 { margin-left: 8px; }
.detail { font-family: monospace; font-size: 12px; color: #606266; }
.page-title { font-size: 16px; font-weight: 600; }
.hash { font-family: monospace; font-size: 12px; color: #909399; }
.ua { font-size: 12px; color: #909399; }
.mt { margin-top: 12px; }
</style>