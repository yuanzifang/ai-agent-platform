<template>
  <div class="page">
    <div class="toolbar">
      <span class="page-title">{{ t('page.settings') }}</span>
    </div>

    <el-card shadow="never">
      <template #header>{{ t('sysConfig.header') }}</template>
      <el-table :data="rows" size="small">
        <el-table-column prop="configKey" :label="t('sysConfig.key')" width="240" />
        <el-table-column :label="t('sysConfig.value')" width="220">
          <template #default="{ row }">
            <el-input v-if="editing === row.configKey" v-model="row.configValue" size="small" />
            <span v-else class="val">{{ row.configValue }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="description" :label="t('sysConfig.desc')" min-width="200" />
        <el-table-column :label="t('sysConfig.actions')" width="130">
          <template #default="{ row }">
            <template v-if="editing === row.configKey">
              <el-button size="small" type="primary" @click="save(row)">{{ t('sysConfig.save') }}</el-button>
              <el-button size="small" @click="editing = ''">{{ t('sysConfig.cancel') }}</el-button>
            </template>
            <el-button v-else size="small" @click="editing = row.configKey">{{ t('sysConfig.edit') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api/http'

const { t } = useI18n()

const rows = ref<any[]>([])
const editing = ref('')

onMounted(load)

async function load() {
  rows.value = await http.get('/admin/config') as any
}

async function save(row: any) {
  await http.put('/admin/config', { configKey: row.configKey, configValue: row.configValue })
  ElMessage.success(`${t('sysConfig.updated')}${row.configKey}`)
  editing.value = ''
}
</script>

<style scoped>
.page { max-width: 900px; margin: 0 auto; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; }
.page-title { font-size: 16px; font-weight: 600; }
.val { font-family: monospace; }
</style>